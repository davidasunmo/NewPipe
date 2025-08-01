package org.schabi.newpipe.player.mediabrowser

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.net.toUri
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector.PlaybackPreparer
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.InfoItem.InfoType
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.local.playlist.LocalPlaylistManager
import org.schabi.newpipe.local.playlist.RemotePlaylistManager
import org.schabi.newpipe.player.playqueue.ChannelTabPlayQueue
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.PlaylistPlayQueue
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.ChannelTabHelper
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.NavigationHelper
import java.util.function.BiConsumer
import java.util.function.Consumer

/**
 * This class is used to cleanly separate the Service implementation (in
 * [org.schabi.newpipe.player.PlayerService]) and the playback preparer implementation (in this
 * file). We currently use the playback preparer only in conjunction with the media browser: the
 * playback preparer will receive the media URLs generated by [MediaBrowserImpl] and will start
 * playback of the corresponding streams or playlists.
 *
 * @param setMediaSessionError takes an error String and an error code from [PlaybackStateCompat],
 * calls `sessionConnector.setCustomErrorMessage(errorString, errorCode)`
 * @param clearMediaSessionError calls `sessionConnector.setCustomErrorMessage(null)`
 * @param onPrepare takes playWhenReady, calls `player.prepare()`; this is needed because
 * `MediaSessionConnector`'s `onPlay()` method calls this class'  [onPrepare] instead of
 * `player.prepare()` if the playback preparer is not null, but we want the original behavior
 */
class MediaBrowserPlaybackPreparer(
    private val context: Context,
    private val setMediaSessionError: BiConsumer<String, Int>, // error string, error code
    private val clearMediaSessionError: Runnable,
    private val onPrepare: Consumer<Boolean>,
) : PlaybackPreparer {
    private val database = NewPipeDatabase.getInstance(context)
    private var disposable: Disposable? = null

    fun dispose() {
        disposable?.dispose()
    }

    //region Overrides
    override fun getSupportedPrepareActions(): Long {
        return PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
    }

    override fun onPrepare(playWhenReady: Boolean) {
        onPrepare.accept(playWhenReady)
    }

    override fun onPrepareFromMediaId(mediaId: String, playWhenReady: Boolean, extras: Bundle?) {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "onPrepareFromMediaId($mediaId, $playWhenReady, $extras)")
        }

        disposable?.dispose()
        disposable = extractPlayQueueFromMediaId(mediaId)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { playQueue ->
                    clearMediaSessionError.run()
                    NavigationHelper.playOnBackgroundPlayer(context, playQueue, playWhenReady)
                },
                { throwable ->
                    Log.e(TAG, "Failed to start playback of media ID [$mediaId]", throwable)
                    onPrepareError()
                }
            )
    }

    override fun onPrepareFromSearch(query: String, playWhenReady: Boolean, extras: Bundle?) {
        onUnsupportedError()
    }

    override fun onPrepareFromUri(uri: Uri, playWhenReady: Boolean, extras: Bundle?) {
        onUnsupportedError()
    }

    override fun onCommand(
        player: Player,
        command: String,
        extras: Bundle?,
        cb: ResultReceiver?
    ): Boolean {
        return false
    }
    //endregion

    //region Errors
    private fun onUnsupportedError() {
        setMediaSessionError.accept(
            context.getString(R.string.content_not_supported),
            PlaybackStateCompat.ERROR_CODE_NOT_SUPPORTED
        )
    }

    private fun onPrepareError() {
        setMediaSessionError.accept(
            context.getString(R.string.error_snackbar_message),
            PlaybackStateCompat.ERROR_CODE_APP_ERROR
        )
    }
    //endregion

    //region Building play queues from playlists and history
    private fun extractLocalPlayQueue(playlistId: Long, index: Int): Single<PlayQueue> {
        return LocalPlaylistManager(database).getPlaylistStreams(playlistId).firstOrError()
            .map { items -> SinglePlayQueue(items.map { it.toStreamInfoItem() }, index) }
    }

    private fun extractRemotePlayQueue(playlistId: Long, index: Int): Single<PlayQueue> {
        return RemotePlaylistManager(database).getPlaylist(playlistId).firstOrError()
            .flatMap { ExtractorHelper.getPlaylistInfo(it.serviceId, it.url, false) }
            // ignore info.errors, i.e. ignore errors about specific items, since there would
            // be no way to show the error properly in Android Auto anyway
            .map { info -> PlaylistPlayQueue(info, index) }
    }

    private fun extractPlayQueueFromMediaId(mediaId: String): Single<PlayQueue> {
        try {
            val mediaIdUri = mediaId.toUri()
            val path = ArrayList(mediaIdUri.pathSegments)
            if (path.isEmpty()) {
                throw parseError(mediaId)
            }

            return when (/*val uriType = */path.removeAt(0)) {
                ID_BOOKMARKS -> extractPlayQueueFromPlaylistMediaId(
                    mediaId,
                    path,
                    mediaIdUri.getQueryParameter(ID_URL)
                )

                ID_HISTORY -> extractPlayQueueFromHistoryMediaId(mediaId, path)

                ID_INFO_ITEM -> extractPlayQueueFromInfoItemMediaId(
                    mediaId,
                    path,
                    mediaIdUri.getQueryParameter(ID_URL) ?: throw parseError(mediaId)
                )

                else -> throw parseError(mediaId)
            }
        } catch (e: ContentNotAvailableException) {
            return Single.error(e)
        }
    }

    @Throws(ContentNotAvailableException::class)
    private fun extractPlayQueueFromPlaylistMediaId(
        mediaId: String,
        path: MutableList<String>,
        url: String?,
    ): Single<PlayQueue> {
        if (path.isEmpty()) {
            throw parseError(mediaId)
        }

        when (val playlistType = path.removeAt(0)) {
            ID_LOCAL, ID_REMOTE -> {
                if (path.size != 2) {
                    throw parseError(mediaId)
                }
                val playlistId = path[0].toLong()
                val index = path[1].toInt()
                return if (playlistType == ID_LOCAL)
                    extractLocalPlayQueue(playlistId, index)
                else
                    extractRemotePlayQueue(playlistId, index)
            }

            ID_URL -> {
                if (path.size != 1 || url == null) {
                    throw parseError(mediaId)
                }

                val serviceId = path[0].toInt()
                return ExtractorHelper.getPlaylistInfo(serviceId, url, false)
                    .map { PlaylistPlayQueue(it) }
            }

            else -> throw parseError(mediaId)
        }
    }

    @Throws(ContentNotAvailableException::class)
    private fun extractPlayQueueFromHistoryMediaId(
        mediaId: String,
        path: List<String>,
    ): Single<PlayQueue> {
        if (path.size != 1) {
            throw parseError(mediaId)
        }

        val streamId = path[0].toLong()
        return database.streamHistoryDAO().getHistory()
            .firstOrError()
            .map { items ->
                val infoItems = items
                    .filter { it.streamId == streamId }
                    .map { it.toStreamInfoItem() }
                SinglePlayQueue(infoItems, 0)
            }
    }

    @Throws(ContentNotAvailableException::class)
    private fun extractPlayQueueFromInfoItemMediaId(
        mediaId: String,
        path: List<String>,
        url: String,
    ): Single<PlayQueue> {
        if (path.size != 2) {
            throw parseError(mediaId)
        }

        val serviceId = path[1].toInt()
        return when (/*val infoItemType = */infoItemTypeFromString(path[0])) {
            InfoType.STREAM -> ExtractorHelper.getStreamInfo(serviceId, url, false)
                .map { SinglePlayQueue(it) }

            InfoType.PLAYLIST -> ExtractorHelper.getPlaylistInfo(serviceId, url, false)
                .map { PlaylistPlayQueue(it) }

            InfoType.CHANNEL -> ExtractorHelper.getChannelInfo(serviceId, url, false)
                .map { info ->
                    val playableTab = info.tabs
                        .firstOrNull { ChannelTabHelper.isStreamsTab(it) }
                        ?: throw ContentNotAvailableException("No streams tab found")
                    return@map ChannelTabPlayQueue(serviceId, ListLinkHandler(playableTab))
                }

            else -> throw parseError(mediaId)
        }
    }
    //endregion

    companion object {
        private val TAG = MediaBrowserPlaybackPreparer::class.simpleName
    }
}
