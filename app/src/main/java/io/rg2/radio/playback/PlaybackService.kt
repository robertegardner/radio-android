package io.rg2.radio.playback

import android.net.Uri
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
import com.google.common.util.concurrent.SettableFuture
import io.rg2.radio.RadioApp
import io.rg2.radio.audio.DuckController
import io.rg2.radio.audio.StereoLevelTap
import io.rg2.radio.audio.TapRenderersFactory
import io.rg2.radio.data.Band
import io.rg2.radio.data.Favorite
import io.rg2.radio.data.Favorites
import io.rg2.radio.data.NowPlaying
import io.rg2.radio.data.NowPlayingRepository
import io.rg2.radio.data.RadioApi
import io.rg2.radio.data.RadioSettings
import io.rg2.radio.data.Station
import io.rg2.radio.data.Stations
import io.rg2.radio.data.TuneRequest
import io.rg2.radio.data.coverArtUrl
import io.rg2.radio.data.toFavorite
import io.rg2.radio.data.trackArtist
import io.rg2.radio.data.trackTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    private lateinit var duck: DuckController

    // Shared with the rest of the app via the application container, so tuning
    // here and polling in the UI use one RadioApi / settings / repository source.
    private val container by lazy { (application as RadioApp).container }
    private val api: RadioApi by lazy { container.api }
    private val repo: NowPlayingRepository by lazy { container.nowPlayingRepository }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Re-prepare backoff after the stream connection drops (e.g. backend restart on tune). */
    private var reconnectAttempts = 0

    /** Live now-playing → session metadata pump; runs only while playing. */
    private var metadataJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        // The renderers factory splices a TeeAudioProcessor into the audio
        // pipeline so the L/R meters see the real decoded stereo PCM (the
        // Visualizer hub only ever gets a mono downmix).
        player = ExoPlayer.Builder(this, TapRenderersFactory(this, StereoLevelTap(container.stereoLevels)))
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
        player.addListener(MetadataUpdater())
        container.audioSessionId.value = player.audioSessionId

        // "Duck on talk": near-mutes the stream during talk/commercials when
        // the option is on. Service-scoped so it works with the screen off.
        duck = DuckController(player, container, scope)

        // Session drives the live-stream wrapper so pause/resume (from the UI,
        // lock-screen, or notification) rejoin the live edge instead of
        // replaying buffered audio. Internal control (reconnect, metadata pump)
        // still operates on the underlying ExoPlayer; ForwardingPlayer relays
        // its events to the session.
        session = MediaLibrarySession.Builder(this, LiveStreamPlayer(player), LibraryCallback()).build()

        // Warm the scanned-station cache so the first tune can auto-select an
        // antenna without ever waiting on the network.
        refreshStations()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        duck.release()
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

    /**
     * The notification / lock-screen show the *player's* current MediaItem
     * metadata, which starts as just the favorite's static label. This pumps the
     * live `now_playing` feed (RDS station, song title/artist, RadioText) into
     * that metadata while playing, so the lock-screen tracks what's actually on
     * air. Only runs while playing — no point polling for a transport nobody can
     * see.
     */
    private inner class MetadataUpdater : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startMetadataUpdates() else stopMetadataUpdates()
        }

        /**
         * Switching source mid-playback (radio ⇄ scanner) keeps `isPlaying` true,
         * so re-evaluate the pump on item changes: scanner items carry their own
         * metadata (stop the radio pump), radio items resume it.
         */
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            if (item?.mediaId?.startsWith(SCANNER_PREFIX) == true) {
                stopMetadataUpdates()
            } else if (player.isPlaying) {
                startMetadataUpdates()
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            // Publish for the reactive visualizer to attach to.
            container.audioSessionId.value = audioSessionId
        }
    }

    private fun startMetadataUpdates() {
        if (metadataJob?.isActive == true) return
        // The scanner streams carry their own metadata; don't pump the FM/AM
        // radio's now_playing feed over them.
        if (player.currentMediaItem?.mediaId?.startsWith(SCANNER_PREFIX) == true) return
        metadataJob = scope.launch {
            repo.nowPlaying().collect { result ->
                val np = result.getOrNull() ?: return@collect
                val current = player.currentMediaItem ?: return@collect
                val updated = liveMetadata(np, current.mediaMetadata, resolveArtUrl(np))
                if (updated != current.mediaMetadata) {
                    // Same URI/mediaId, metadata-only change → seamless, no re-buffer.
                    player.replaceMediaItem(
                        player.currentMediaItemIndex,
                        current.buildUpon().setMediaMetadata(updated).build(),
                    )
                }
            }
        }
    }

    /**
     * Cover-art URL for the session metadata: prefer art the backend already
     * fetched, else the iTunes fallback (same priority as the UI's artwork
     * flow). The repository caches by track — including misses — so calling
     * this on every 1 s poll is a map hit, not a network call.
     */
    private suspend fun resolveArtUrl(np: NowPlaying): String? {
        np.coverArtUrl()?.let { return it }
        val artist = np.trackArtist() ?: return null
        val title = np.trackTitle() ?: return null
        return container.artworkRepository.artworkUrl(artist, title)
    }

    private fun stopMetadataUpdates() {
        metadataJob?.cancel()
        metadataJob = null
    }

    /**
     * Merge live [np] over the current item's metadata (which carries the
     * favorite label/sublabel as fallback). Notification line 1 = song title or
     * station; line 2 = song artist, else RadioText, else the favorite sublabel.
     *
     * [artUrl] lands as the artworkUri: the session's bitmap loader fetches it
     * and hands the bitmap to the platform session, which is what the
     * notification, Android Auto, and Bluetooth AVRCP cover art (head units
     * speaking AVRCP 1.6+) all display. Null clears the art for talk content.
     */
    private fun liveMetadata(np: NowPlaying, base: MediaMetadata, artUrl: String?): MediaMetadata {
        val station = np.rds?.ps?.takeIf { it.isNotBlank() }
            ?: np.fcc?.call?.takeIf { it.isNotBlank() }
            ?: base.station?.toString()
            ?: base.title?.toString()
        val songTitle = np.trackTitle()
        val songArtist = np.trackArtist()

        val line1 = songTitle ?: station
        val line2 = songArtist
            ?: np.rds?.rt?.takeIf { it.isNotBlank() }
            ?: base.subtitle?.toString()

        return base.buildUpon()
            .setTitle(line1)
            .setArtist(line2)
            .setStation(station)
            .setArtworkUri(artUrl?.let(Uri::parse))
            .build()
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
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = when (parentId) {
            Favorites.ROOT_ID -> {
                val items = Favorites.SEED.map(::favoriteBrowseItem) + stationsFolderItem()
                Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            }
            STATIONS_ID -> {
                // Scanned stations come from the backend; resolve asynchronously.
                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                scope.launch {
                    try {
                        val s = stations()
                        if (s == null) {
                            // Backend unreachable and no cache: a real error,
                            // not a deceptively-successful empty folder.
                            future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO))
                        } else {
                            val items = (s.fm + s.am).map(::stationBrowseItem)
                            future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                        }
                    } finally {
                        // Service destroyed mid-fetch (scope cancelled): complete
                        // anyway so the browser can't hang on a spinner forever.
                        if (!future.isDone) future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                    }
                }
                future
            }
            else -> Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val fav = Favorite.fromMediaId(mediaId)
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
                when {
                    fav != null -> {
                        tune(fav)
                        streamItem(fav)
                    }
                    // Scanner stream/recording: the controller carries the target
                    // URL in request metadata (survives IPC); resolve it here so
                    // the shared session plays it like any other item.
                    item.mediaId.startsWith(SCANNER_PREFIX) -> scannerItem(item)
                    // Play the current station without retuning (cold-start Play).
                    item.mediaId == Favorites.LIVE_ID -> liveStreamItem()
                    // Already-resolved item (has a URI) — pass through unchanged.
                    item.localConfiguration != null -> item
                    else -> liveStreamItem()
                }
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }

    /** Fire-and-forget tune; playback of the (unchanging) stream URL continues regardless. */
    private fun tune(fav: Favorite) {
        // Antenna auto-select (mirrors the web UI): the scan's best antenna
        // rides the tune body so freq + antenna land in ONE stream restart.
        // Two deliberate limits: only the already-cached scan is consulted —
        // the tune POST must never wait behind a stations fetch (in the car
        // that could delay a preset tap by a network timeout) — and only when
        // actually CHANGING station, so re-tapping the current one can't
        // silently revert a manual antenna choice from the RF chips.
        val antenna = if (isStationChange(fav)) bestCachedAntenna(fav) else null
        lastTuned = fav
        scope.launch {
            runCatching { api.tune(TuneRequest(freq = fav.freq, band = fav.band, antenna = antenna)) }
                .onSuccess { resp ->
                    if (resp.ok) Log.i(TAG, "tuned ${fav.label}" + (antenna?.let { " on $it" } ?: ""))
                    else Log.w(TAG, "tune ${fav.label} rejected: ${resp.error}")
                }
                .onFailure { Log.w(TAG, "tune ${fav.label} failed", it) }
            refreshStations() // keep the cache warm for the next tune / browse
        }
    }

    private fun isStationChange(fav: Favorite): Boolean {
        val last = lastTuned ?: return true
        return last.band != fav.band || abs(last.freq - fav.freq) >= freqEpsilon(fav.band)
    }

    /**
     * The scanned best antenna for [fav]'s frequency from the cached scan, or
     * null (key omitted → backend keeps the current antenna) when unknown.
     */
    private fun bestCachedAntenna(fav: Favorite): String? {
        val s = stationsCache ?: return null
        val list = if (fav.band == Band.AM) s.am else s.fm
        return list.firstOrNull { abs(it.tuneFreq - fav.freq) < freqEpsilon(fav.band) }?.antenna
    }

    private fun freqEpsilon(band: Band): Double = if (band == Band.AM) 0.5 else 0.05

    /** Refresh the scanned-station cache in the background (never awaited by tune). */
    private fun refreshStations() {
        scope.launch { runCatching { api.stations() }.onSuccess { stationsCache = it } }
    }

    /** Fetch the scanned station list, falling back to the last good copy. */
    private suspend fun stations(): Stations? =
        runCatching { api.stations() }.getOrNull()?.also { stationsCache = it } ?: stationsCache

    private var stationsCache: Stations? = null

    /** The last tune this service issued — the "are we changing station?" reference. */
    private var lastTuned: Favorite? = null

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

    /**
     * Playable-leaf (no-URI) representation shown in the Android Auto tree.
     * Must be isPlayable=true / isBrowsable=false — a "browsable" preset
     * renders as a folder on Auto and can never be tuned from there.
     */
    private fun favoriteBrowseItem(fav: Favorite): MediaItem =
        MediaItem.Builder()
            .setMediaId(fav.mediaId)
            .setMediaMetadata(stationMetadata(fav))
            .build()

    /** The "Stations" folder under the root — all scanned FM + AM stations. */
    private fun stationsFolderItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(STATIONS_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Stations")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                    .build(),
            )
            .build()

    /**
     * A scanned station in the Auto tree. Same mediaId scheme as favorites, so
     * selection resolves through [Favorite.fromMediaId] → tune like a preset.
     */
    private fun stationBrowseItem(station: Station): MediaItem {
        val fav = station.toFavorite()
        val detail = listOfNotNull(
            fav.sublabel.takeIf { it.isNotBlank() },
            station.snrDb?.let { "%.0f dB".format(it) },
        ).joinToString(" · ")
        return MediaItem.Builder()
            .setMediaId(fav.mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(fav.label)
                    .setSubtitle(detail.takeIf { it.isNotBlank() })
                    .setStation(fav.label)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .build(),
            )
            .build()
    }

    /**
     * Resolve a scanner item the controller sent. The playable URL rides in
     * [MediaItem.requestMetadata] (`mediaUri`), which — unlike `localConfiguration`
     * — is preserved across the controller→session IPC, so we rebuild the item
     * with it as the URI. The controller-supplied [MediaMetadata] (source label /
     * talkgroup) is kept as-is; the radio metadata pump is gated off for these.
     */
    private fun scannerItem(item: MediaItem): MediaItem {
        val uri = item.requestMetadata.mediaUri
            ?: return item // nothing to play; pass through (will no-op)
        return item.buildUpon()
            .setUri(uri)
            .build()
    }

    /** Generic live-stream item (no favorite, no tune); metadata fills in from the pump. */
    private fun liveStreamItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(Favorites.LIVE_ID)
            .setUri(RadioSettings.DEFAULT_STREAM_URL)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Radio")
                    .setStation("Radio")
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .build(),
            )
            .build()

    /** Playable representation backed by the Icecast stream URI. */
    private fun streamItem(fav: Favorite): MediaItem =
        MediaItem.Builder()
            .setMediaId(fav.mediaId)
            .setUri(RadioSettings.DEFAULT_STREAM_URL)
            .setMediaMetadata(stationMetadata(fav))
            .build()

    private fun stationMetadata(fav: Favorite): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(fav.label)
            .setSubtitle(fav.sublabel)
            .setStation(fav.label)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .build()

    companion object {
        private const val TAG = "PlaybackService"
        private const val MAX_RECONNECTS = 5
        private const val RECONNECT_DELAY_MS = 2_000L

        /** mediaId prefix for EMS/ATC scanner streams + recordings (see ui.ScannerScreen). */
        const val SCANNER_PREFIX = "scanner:"

        /** Browse-tree id of the scanned-stations folder. */
        const val STATIONS_ID = "stations"
    }
}
