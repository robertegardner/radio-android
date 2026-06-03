package io.rg2.radio.playback

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
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.rg2.radio.data.Band
import io.rg2.radio.data.Favorite
import io.rg2.radio.data.Favorites
import io.rg2.radio.data.InMemoryRadioSettings
import io.rg2.radio.data.RadioApi
import io.rg2.radio.data.RadioSettings
import io.rg2.radio.data.TuneRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The background playback engine. A [MediaLibraryService] gives us three things
 * at once:
 *  - an ExoPlayer playing the single Icecast MP3 stream,
 *  - a media session driving the notification + lock-screen transport controls
 *    (promoted to a foreground service automatically while playing), and
 *  - a browsable library tree that Android Auto renders (favorites → tap to tune).
 *
 * The stream URL never changes; selecting a station means POSTing to
 * `/api/tune` and continuing to listen to the same stream. So when a controller
 * (the app UI or Android Auto) asks to play a favorite, we resolve it to the
 * stream URI in [onAddMediaItems] and fire the tune in the background.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession

    // TODO: swap InMemoryRadioSettings for the encrypted settings store once the
    // settings screen lands. Base URL default + no auth works against the live
    // backend today (tune auth isn't enforced yet).
    private val settings: RadioSettings = InMemoryRadioSettings()
    private val api = RadioApi({ settings })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Re-prepare backoff after the stream connection drops (e.g. backend restart on tune). */
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
            .setHandleAudioBecomingNoisy(true)   // pause when headphones unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)     // keep streaming with screen off
            .build()

        player.addListener(ReconnectListener())

        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * A live MP3 stream has no real end; if the connection drops (commonly when
     * the backend restarts the stream on a tune), re-prepare with a short
     * capped backoff so audio resumes on the newly-tuned station.
     */
    private inner class ReconnectListener : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (reconnectAttempts >= MAX_RECONNECTS) {
                Log.w(TAG, "giving up reconnect after $reconnectAttempts attempts", error)
                return
            }
            val attempt = ++reconnectAttempts
            Log.i(TAG, "stream error (${error.errorCodeName}); reconnect #$attempt")
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

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != Favorites.ROOT_ID) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            }
            val items = ImmutableList.copyOf(Favorites.SEED.map(::favoriteBrowseItem))
            return Futures.immediateFuture(LibraryResult.ofItemList(items, params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val fav = Favorites.byMediaId(mediaId)
                ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(favoriteBrowseItem(fav), null))
        }

        /**
         * Controllers (the app and Android Auto) hand us favorite items that
         * carry only a mediaId. Resolve each to the playable Icecast stream and
         * fire the tune. Covers `setMediaItem(s)` too — the default
         * `onSetMediaItems` delegates here.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val fav = Favorite.fromMediaId(item.mediaId)
                if (fav != null) {
                    tune(fav)
                    streamItem(fav)
                } else {
                    // Already-resolved item (has a URI) — pass through unchanged.
                    item
                }
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }

    /** Fire-and-forget tune; playback of the (unchanging) stream URL continues regardless. */
    private fun tune(fav: Favorite) {
        scope.launch {
            runCatching { api.tune(TuneRequest(freq = fav.freq, band = fav.band)) }
                .onSuccess { resp ->
                    if (resp.ok) Log.i(TAG, "tuned ${fav.label}")
                    else Log.w(TAG, "tune ${fav.label} rejected: ${resp.error}")
                }
                .onFailure { Log.w(TAG, "tune ${fav.label} failed", it) }
        }
    }

    private fun rootItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(Favorites.ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Radio")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                    .build(),
            )
            .build()

    /** Browsable (no-URI) representation shown in the Android Auto tree. */
    private fun favoriteBrowseItem(fav: Favorite): MediaItem =
        MediaItem.Builder()
            .setMediaId(fav.mediaId)
            .setMediaMetadata(stationMetadata(fav, browsable = true))
            .build()

    /** Playable representation backed by the Icecast stream URI. */
    private fun streamItem(fav: Favorite): MediaItem =
        MediaItem.Builder()
            .setMediaId(fav.mediaId)
            .setUri(RadioSettings.DEFAULT_STREAM_URL)
            .setMediaMetadata(stationMetadata(fav, browsable = false))
            .build()

    private fun stationMetadata(fav: Favorite, browsable: Boolean): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(fav.label)
            .setSubtitle(fav.sublabel)
            .setStation(fav.label)
            .setIsBrowsable(browsable)
            .setIsPlayable(!browsable)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .build()

    companion object {
        private const val TAG = "PlaybackService"
        private const val MAX_RECONNECTS = 5
        private const val RECONNECT_DELAY_MS = 2_000L
    }
}
