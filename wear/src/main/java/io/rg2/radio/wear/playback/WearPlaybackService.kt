package io.rg2.radio.wear.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.rg2.radio.data.Favorite
import io.rg2.radio.data.RadioApi
import io.rg2.radio.data.RadioSettings
import io.rg2.radio.data.TuneRequest
import io.rg2.radio.wear.WearApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The watch's standalone playback engine. A [MediaSessionService] gives an
 * ExoPlayer playing the chosen Icecast MP3 stream plus a media session driving
 * the Wear system media controls (foreground while playing).
 *
 * Resolution mirrors the phone service but trimmed (no Android Auto tree):
 *  - a **radio favorite** mediaId → POST `/api/tune`, then play the FM stream;
 *  - a **scanner** mediaId (prefix [SCANNER_PREFIX]) → play the URL carried in
 *    `requestMetadata.mediaUri` (the scanner source switch already happened in
 *    the ViewModel before this item was set).
 */
@OptIn(UnstableApi::class)
class WearPlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession

    private val container by lazy { (application as WearApp).container }
    private val api: RadioApi by lazy { container.api }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var reconnectAttempts = 0

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(ReconnectListener())

        session = MediaSession.Builder(this, LiveStreamPlayer(player))
            .setCallback(Callback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onDestroy() {
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    private inner class ReconnectListener : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (reconnectAttempts >= MAX_RECONNECTS) {
                Log.w(TAG, "giving up reconnect after $reconnectAttempts", error)
                return
            }
            val attempt = ++reconnectAttempts
            scope.launch {
                delay(RECONNECT_DELAY_MS * attempt)
                player.prepare()
                player.play()
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) reconnectAttempts = 0
        }
    }

    private inner class Callback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val fav = Favorite.fromMediaId(item.mediaId)
                when {
                    fav != null -> {
                        tune(fav)
                        streamItem(fav)
                    }
                    item.mediaId.startsWith(SCANNER_PREFIX) -> scannerItem(item)
                    item.localConfiguration != null -> item
                    else -> item
                }
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }

    private fun tune(fav: Favorite) {
        scope.launch {
            runCatching { api.tune(TuneRequest(freq = fav.freq, band = fav.band)) }
                .onSuccess { if (it.ok) Log.i(TAG, "tuned ${fav.label}") else Log.w(TAG, "tune rejected: ${it.error}") }
                .onFailure { Log.w(TAG, "tune failed", it) }
        }
    }

    private fun streamItem(fav: Favorite): MediaItem =
        MediaItem.Builder()
            .setMediaId(fav.mediaId)
            .setUri(RadioSettings.DEFAULT_STREAM_URL)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(fav.label)
                    .setStation(fav.label)
                    .setSubtitle(fav.sublabel)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()

    private fun scannerItem(item: MediaItem): MediaItem {
        val uri = item.requestMetadata.mediaUri ?: return item
        return item.buildUpon().setUri(uri).build()
    }

    companion object {
        private const val TAG = "WearPlaybackService"
        private const val MAX_RECONNECTS = 5
        private const val RECONNECT_DELAY_MS = 2_000L

        /** mediaId prefix for scanner streams (URL carried in requestMetadata.mediaUri). */
        const val SCANNER_PREFIX = "scanner:"
    }
}
