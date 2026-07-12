package io.rg2.radio.ui

import android.content.ComponentName
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.rg2.radio.BuildConfig
import io.rg2.radio.RadioApp
import io.rg2.radio.audio.DuckState
import io.rg2.radio.audio.DuckStatus
import io.rg2.radio.audio.StereoLevels
import io.rg2.radio.data.Antennas
import io.rg2.radio.data.Band
import io.rg2.radio.data.Favorite
import io.rg2.radio.data.Favorites
import io.rg2.radio.data.NowPlaying
import io.rg2.radio.data.Station
import io.rg2.radio.data.Stations
import io.rg2.radio.data.toFavorite
import io.rg2.radio.data.trackArtist
import io.rg2.radio.data.trackTitle
import io.rg2.radio.playback.PlaybackService
import io.rg2.radio.ui.theme.Amber
import io.rg2.radio.ui.theme.AmberDim
import io.rg2.radio.ui.theme.RadioSurface
import io.rg2.radio.ui.theme.SignalBad
import io.rg2.radio.ui.theme.SignalGood
import io.rg2.radio.ui.theme.SignalWarn
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

// ---------------------------------------------------------------------------
// Route: connects the MediaController + ViewModel to the stateless screen.
// ---------------------------------------------------------------------------

@Composable
fun NowPlayingRoute(
    modifier: Modifier = Modifier,
    viewModel: NowPlayingViewModel = viewModel(),
) {
    val controller = rememberMediaController()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val artworkUrl by viewModel.artworkUrl.collectAsStateWithLifecycle()
    val stations by viewModel.stations.collectAsStateWithLifecycle()
    val bitrate by viewModel.bitrate.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val container = (context.applicationContext as RadioApp).container
    val audioSessionId by container.audioSession.collectAsStateWithLifecycle()
    val duckEnabled by container.duckEnabled.collectAsStateWithLifecycle()
    val duckStatus by container.duckStatus.collectAsStateWithLifecycle()

    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var isRadioSource by remember { mutableStateOf(true) }
    var captionsOn by rememberSaveable { mutableStateOf(true) }
    var vizStyleName by rememberSaveable { mutableStateOf(VizStyle.BARS.name) }
    val vizStyle = VizStyle.valueOf(vizStyleName)

    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose {}
        fun sync() {
            isPlaying = c.isPlaying
            isBuffering = c.playbackState == Player.STATE_BUFFERING
            // The one shared player also plays scanner streams; the tuner
            // panel's L/R meters must not animate to scanner audio.
            isRadioSource =
                c.currentMediaItem?.mediaId?.startsWith(PlaybackService.SCANNER_PREFIX) != true
        }
        sync()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) = sync()
            override fun onPlaybackStateChanged(state: Int) = sync()
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) = sync()
        }
        c.addListener(listener)
        onDispose { c.removeListener(listener) }
    }

    NowPlayingScreen(
        modifier = modifier,
        state = nowPlaying,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        enabled = controller != null,
        artworkUrl = artworkUrl,
        stations = stations,
        bitrate = bitrate,
        stereoLevels = container.stereoLevels,
        metersActive = isPlaying && isRadioSource,
        captionsOn = captionsOn,
        onToggleCaptions = { captionsOn = it },
        vizStyle = vizStyle,
        onVizStyleChange = { vizStyleName = it.name },
        audioSessionId = audioSessionId,
        duckEnabled = duckEnabled,
        duckStatus = duckStatus,
        onToggleDuck = { container.setDuckEnabled(it) },
        onSetStereo = viewModel::setStereo,
        onSetAntenna = viewModel::setAntenna,
        onSetBitrate = viewModel::setBitrate,
        onPlayPause = {
            val c = controller ?: return@NowPlayingScreen
            when {
                c.currentMediaItem == null -> {
                    // Cold start: play whatever's currently tuned, no retune.
                    c.setMediaItem(MediaItem.Builder().setMediaId(Favorites.LIVE_ID).build())
                    c.prepare()
                    c.play()
                }
                c.isPlaying -> c.pause()
                else -> c.play()
            }
        },
        onSelectFavorite = { fav ->
            val c = controller ?: return@NowPlayingScreen
            c.setMediaItem(MediaItem.Builder().setMediaId(fav.mediaId).build())
            c.prepare()
            c.play()
        },
    )
}

// ---------------------------------------------------------------------------
// Stateless screen.
// ---------------------------------------------------------------------------

@Composable
private fun NowPlayingScreen(
    state: Polled<NowPlaying>,
    isPlaying: Boolean,
    isBuffering: Boolean,
    enabled: Boolean,
    artworkUrl: String?,
    stations: Polled<Stations>,
    bitrate: String?,
    stereoLevels: StateFlow<StereoLevels>,
    metersActive: Boolean,
    captionsOn: Boolean,
    onToggleCaptions: (Boolean) -> Unit,
    vizStyle: VizStyle,
    onVizStyleChange: (VizStyle) -> Unit,
    audioSessionId: Int,
    duckEnabled: Boolean,
    duckStatus: DuckStatus,
    onToggleDuck: (Boolean) -> Unit,
    onSetStereo: (Boolean) -> Unit,
    onSetAntenna: (String) -> Unit,
    onSetBitrate: (String) -> Unit,
    onPlayPause: () -> Unit,
    onSelectFavorite: (Favorite) -> Unit,
    modifier: Modifier = Modifier,
) {
    val np = state.data
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        StatusHeader(state)
        TunerDisplay(np, artworkUrl, stereoLevels, metersActive)
        RfControls(
            np = np,
            bitrate = bitrate,
            onSetStereo = onSetStereo,
            onSetAntenna = onSetAntenna,
            onSetBitrate = onSetBitrate,
        )
        TrackIdTile(np)
        ProgramPane(
            np = np,
            captionsOn = captionsOn,
            onToggleCaptions = onToggleCaptions,
            vizStyle = vizStyle,
            onVizStyleChange = onVizStyleChange,
            isPlaying = isPlaying,
            audioSessionId = audioSessionId,
            duckEnabled = duckEnabled,
            duckStatus = duckStatus,
            onToggleDuck = onToggleDuck,
        )
        TransportButton(
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            enabled = enabled,
            onClick = onPlayPause,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        PresetsSection(
            activeFavorite = np.activeFavorite(),
            enabled = enabled,
            onSelect = onSelectFavorite,
        )
        StationsSection(
            stations = stations,
            np = np,
            enabled = enabled,
            onSelect = onSelectFavorite,
        )
    }
}

@Composable
private fun StatusHeader(state: Polled<NowPlaying>) {
    val (dot, label) = when {
        state.data != null -> SignalGood to "ON AIR"
        state.error != null -> SignalBad to "OFFLINE"
        else -> SignalWarn to "CONNECTING"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(
                text = "RG2 RADIO",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 4.sp,
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            letterSpacing = 2.sp,
        )
    }
}

@Composable
private fun TunerDisplay(
    np: NowPlaying?,
    artworkUrl: String?,
    levelsFlow: StateFlow<StereoLevels>,
    metersActive: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
    ) {
        Box {
            // Album art behind the panel (music only), under a scrim so the
            // amber readout and text stay legible.
            if (artworkUrl != null) {
                AsyncImage(
                    model = artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    RadioSurface.copy(alpha = 0.62f),
                                    RadioSurface.copy(alpha = 0.90f),
                                ),
                            ),
                        ),
                )
            }

            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BandChip(np?.band)
                    Spacer(Modifier.weight(1f))
                    // The backend gates `pilot` on FM + stereo mode + a fresh
                    // 19 kHz lock, so it alone means "really receiving stereo".
                    StereoLed(lit = np?.pilot == true)
                }

                val unit = when (np?.band) {
                    Band.AM -> "kHz"
                    Band.FM -> "MHz"
                    null -> ""
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = np?.freq ?: "—––.–",
                        color = Amber,
                        fontSize = 60.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    if (unit.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = unit,
                            color = Amber,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }

                Text(
                    text = np.stationName(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                np.placeLine()?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                LevelMeters(levelsFlow, metersActive)
            }
        }
    }
}

/** Classic tuner STEREO lamp: lit only on a true FM pilot lock. */
@Composable
private fun StereoLed(lit: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .then(
                    if (lit) Modifier.background(Amber)
                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                ),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "STEREO",
            color = if (lit) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * L/R channel level meters fed by the player's PCM tap ([StereoLevels]) —
 * genuinely independent needles when the stream is stereo, identical on mono.
 * Collects the ~40 Hz level flow HERE so only the two bars recompose per
 * audio buffer, not the whole screen. [active] is false when paused or when
 * the shared player is on a scanner stream — meters rest at zero.
 */
@Composable
private fun LevelMeters(levelsFlow: StateFlow<StereoLevels>, active: Boolean) {
    val levels by levelsFlow.collectAsStateWithLifecycle()
    val shown = if (active) levels else StereoLevels()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        MeterBar("L", shown.left)
        MeterBar("R", shown.right)
    }
}

@Composable
private fun MeterBar(label: String, level: Float) {
    val animated by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 70),
        label = "meter$label",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(14.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animated)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(listOf(AmberDim, Amber))),
            )
        }
    }
}

/**
 * Dedicated, static "what's playing and how we know" tile between the tuner and
 * the captions/visualizer. Shows the derived artist/title, a badge declaring the
 * identification source (RDS / Chromaprint / Lyric match), and the raw RDS feed
 * so the live broadcast data is always visible.
 */
@Composable
private fun TrackIdTile(np: NowPlaying?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val title = np?.trackTitle()
            val artist = np?.trackArtist()
            val source = np?.track?.source

            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("TRACK ID")
                Spacer(Modifier.weight(1f))
                SourceBadge(source)
            }

            if (title != null || artist != null) {
                title?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                artist?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                np?.track?.album?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = "No song identified yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp,
                )
            }

            RdsReadout(np)
        }
    }
}

/** Badge naming the identification source, or "—" when nothing is identified. */
@Composable
private fun SourceBadge(source: String?) {
    val label = when (source) {
        "rds" -> "RDS"
        "acoustid" -> "CHROMAPRINT"
        "lyrics" -> "LYRIC MATCH"
        else -> "—"
    }
    val identified = source != null
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (identified) Modifier.background(Amber)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = if (identified) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Raw RDS feed (PS / RadioText / program type) so live broadcast data is always visible. */
@Composable
private fun RdsReadout(np: NowPlaying?) {
    val rds = np?.rds ?: return
    val ps = rds.ps?.takeIf { it.isNotBlank() }
    val rt = rds.rt?.takeIf { it.isNotBlank() }
    val pty = rds.progType?.takeIf { it.isNotBlank() }
    if (ps == null && rt == null && pty == null) return

    Spacer(Modifier.height(4.dp))
    val head = listOfNotNull(ps?.let { "PS $it" }, pty?.let { "· $it" }).joinToString(" ")
    if (head.isNotBlank()) {
        Text(
            text = "RDS  $head",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    rt?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BandChip(band: Band?) {
    val text = band?.name ?: "—"
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Amber, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text = text, color = Amber, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * RF settings mirrored from the backend web UI: antenna port, FM stereo/mono,
 * and stream bitrate. Every change restarts the stream server-side (a ~2 s
 * drop the reconnect logic rides out); current state comes from the polls, so
 * the chips light up when the backend confirms.
 */
@Composable
private fun RfControls(
    np: NowPlaying?,
    bitrate: String?,
    onSetStereo: (Boolean) -> Unit,
    onSetAntenna: (String) -> Unit,
    onSetBitrate: (String) -> Unit,
) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("RF")
            Spacer(Modifier.weight(1f))
            np?.antenna?.let {
                Text(
                    text = it.uppercase(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Antennas.ALL.forEach { antenna ->
                OptionChip(
                    text = Antennas.shortLabel(antenna),
                    selected = np?.antenna == antenna,
                    // The HF+ is a separate AM-only device; FM always runs on the dx-R2.
                    enabled = np != null && (antenna != Antennas.HF_PLUS || np.band == Band.AM),
                    onClick = { onSetAntenna(antenna) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val fm = np?.band == Band.FM
            OptionChip("ST", selected = np?.stereo == true, enabled = fm) { onSetStereo(true) }
            OptionChip("MONO", selected = np?.stereo == false, enabled = fm) { onSetStereo(false) }
            Spacer(Modifier.weight(1f))
            OptionChip("128K", selected = bitrate == "128k", enabled = np != null) { onSetBitrate("128k") }
            OptionChip("256K", selected = bitrate == "256k", enabled = np != null) { onSetBitrate("256k") }
        }
    }
}

/** Small amber option chip: filled when selected, outlined otherwise. */
@Composable
private fun OptionChip(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = if (selected) Amber else MaterialTheme.colorScheme.surface
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.outline
        selected -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, if (selected) Amber else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled && !selected) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 12.sp,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Live captions (Cardinals play-by-play) when the CC toggle is on and captions
 * are available; otherwise the audio visualizer fills the space. Song/lyric
 * info lives in the tuner display, so it's preserved either way.
 */
@Composable
private fun ProgramPane(
    np: NowPlaying?,
    captionsOn: Boolean,
    onToggleCaptions: (Boolean) -> Unit,
    vizStyle: VizStyle,
    onVizStyleChange: (VizStyle) -> Unit,
    isPlaying: Boolean,
    audioSessionId: Int,
    duckEnabled: Boolean,
    duckStatus: DuckStatus,
    onToggleDuck: (Boolean) -> Unit,
) {
    val caption = np?.caption?.text?.takeIf { np.mode == "captions" && it.isNotBlank() }
    val showCaption = captionsOn && caption != null

    InfoCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel(if (showCaption) "LIVE CAPTIONS" else "VISUALIZER")
            Spacer(Modifier.weight(1f))
            DuckToggle(duckEnabled, duckStatus, onToggleDuck)
            CaptionToggle(captionsOn, onToggleCaptions)
        }
        // Live classifier readout while DUCK is on — the tuning surface for
        // the talk/music heuristic (see TalkMusicClassifier).
        if (duckEnabled) {
            val line = when {
                duckStatus.active ->
                    "duck: %s · score %.2f%s".format(
                        duckStatus.state.name.lowercase(),
                        duckStatus.score,
                        if (duckStatus.state == DuckState.TALK) " · volume ducked" else "",
                    )
                duckStatus.note != null -> "duck: ${duckStatus.note}"
                else -> "duck: waiting for playback"
            }
            Text(
                text = line,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (showCaption) {
            Text(
                text = caption!!,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 17.sp,
                lineHeight = 24.sp,
            )
        } else {
            VisualizerPane(
                style = vizStyle,
                onStyleChange = onVizStyleChange,
                isPlaying = isPlaying,
                audioSessionId = audioSessionId,
            )
        }
    }
}

/**
 * "Duck on talk" option chip: OFF (outline) → ON (amber). While the classifier
 * is actively ducking it reads DUCKED. Enabling requests RECORD_AUDIO if
 * needed (the audio tap is the same mechanism as the reactive visualizers).
 */
@Composable
private fun DuckToggle(on: Boolean, status: DuckStatus, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onChange(true) }

    val ducked = on && status.active && status.state == DuckState.TALK
    val bg = if (on) Amber else MaterialTheme.colorScheme.surface
    val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, if (on) Amber else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .clickable {
                when {
                    on -> onChange(false)
                    hasRecordPermission(context) -> onChange(true)
                    else -> permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = if (ducked) "DUCKED" else "DUCK",
            color = fg,
            fontSize = 12.sp,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CaptionToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val bg = if (on) Amber else MaterialTheme.colorScheme.surface
    val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, if (on) Amber else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .clickable { onChange(!on) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = if (on) "CC ON" else "CC OFF",
            color = fg,
            fontSize = 12.sp,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Amber,
        fontSize = 11.sp,
        letterSpacing = 2.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun TransportButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val glyph = MaterialTheme.colorScheme.onPrimary
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(88.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                isBuffering -> CircularProgressIndicator(
                    color = glyph,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(34.dp),
                )
                isPlaying -> Canvas(Modifier.size(32.dp)) { drawPause(glyph) }
                else -> Canvas(Modifier.size(34.dp)) { drawPlay(glyph) }
            }
        }
    }
}

@Composable
private fun PresetsSection(
    activeFavorite: Favorite?,
    enabled: Boolean,
    onSelect: (Favorite) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("PRESETS")
        Favorites.SEED.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { fav ->
                    PresetCard(
                        fav = fav,
                        active = fav == activeFavorite,
                        enabled = enabled,
                        onClick = { onSelect(fav) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PresetCard(
    fav: Favorite,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container =
        if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    val borderMod = if (active) Modifier.border(1.5.dp, Amber, RoundedCornerShape(16.dp)) else Modifier
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.then(borderMod),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = fav.label,
                color = if (active) Amber else MaterialTheme.colorScheme.onSurface,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = fav.sublabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Browsable scanned-station list (collapsed by default — it can run long).
 * Rows show call/city, scan SNR, and the best antenna from the multi-antenna
 * sweep; tapping tunes through the same favorite/mediaId path as presets, so
 * the service auto-selects that antenna on the way in.
 */
@Composable
private fun StationsSection(
    stations: Polled<Stations>,
    np: NowPlaying?,
    enabled: Boolean,
    onSelect: (Favorite) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val s = stations.data
    val count = (s?.fm?.size ?: 0) + (s?.am?.size ?: 0)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            SectionLabel("STATIONS")
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (count > 0) "$count scanned" else "no scan data",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (expanded) "HIDE" else "SHOW",
                color = Amber,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (expanded && s != null) {
            StationBandList("FM", s.fm, np, enabled, onSelect)
            StationBandList("AM", s.am, np, enabled, onSelect)
        }
    }
}

@Composable
private fun StationBandList(
    header: String,
    list: List<Station>,
    np: NowPlaying?,
    enabled: Boolean,
    onSelect: (Favorite) -> Unit,
) {
    if (list.isEmpty()) return
    Text(
        text = header,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        letterSpacing = 2.sp,
        fontWeight = FontWeight.Bold,
    )
    list.forEach { station ->
        StationRow(
            station = station,
            active = np.isTunedTo(station),
            enabled = enabled,
            onClick = { onSelect(station.toFavorite()) },
        )
    }
}

@Composable
private fun StationRow(
    station: Station,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val fav = station.toFavorite()
    val borderMod = if (active) Modifier.border(1.5.dp, Amber, RoundedCornerShape(12.dp)) else Modifier
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().then(borderMod),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = Favorite.formatFreq(station.band, station.tuneFreq),
                color = if (active) Amber else MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(64.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = station.call ?: fav.label,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (fav.sublabel.isNotBlank()) {
                    Text(
                        text = fav.sublabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                station.snrDb?.let {
                    Text(
                        text = "%.0f dB".format(it),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                station.antenna?.let {
                    Text(
                        text = Antennas.shortLabel(it),
                        color = Amber,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** Whether [station] matches the currently-tuned band/freq. */
private fun NowPlaying?.isTunedTo(station: Station): Boolean {
    val np = this ?: return false
    val f = np.freq?.toDoubleOrNull() ?: return false
    if (np.band != station.band) return false
    val epsilon = if (station.band == Band.AM) 0.5 else 0.05
    return abs(station.tuneFreq - f) < epsilon
}

// ---------------------------------------------------------------------------
// Canvas glyphs (no icon dependency).
// ---------------------------------------------------------------------------

private fun DrawScope.drawPlay(color: androidx.compose.ui.graphics.Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.22f, h * 0.12f)
        lineTo(w * 0.22f, h * 0.88f)
        lineTo(w * 0.86f, h * 0.50f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawPause(color: androidx.compose.ui.graphics.Color) {
    val w = size.width
    val h = size.height
    val barW = w * 0.24f
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.12f),
        size = androidx.compose.ui.geometry.Size(barW, h * 0.76f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * 0.3f),
    )
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(w * 0.58f, h * 0.12f),
        size = androidx.compose.ui.geometry.Size(barW, h * 0.76f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * 0.3f),
    )
}

// ---------------------------------------------------------------------------
// MediaController binding.
// ---------------------------------------------------------------------------

@Composable
internal fun rememberMediaController(): MediaController? {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            { runCatching { controller = future.get() } },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            MediaController.releaseFuture(future)
            controller = null
        }
    }
    return controller
}

// ---------------------------------------------------------------------------
// NowPlaying display helpers.
// ---------------------------------------------------------------------------

private fun NowPlaying?.stationName(): String {
    val np = this ?: return "—"
    // Prefer the stable FCC call sign; the RDS PS is often a scrolling/dynamic
    // field (e.g. "KGMOit") that flickers if shown as the station identity.
    return np.fcc?.call?.takeIf { it.isNotBlank() }
        ?: np.rds?.ps?.takeIf { it.isNotBlank() }
        ?: "—"
}

private fun NowPlaying?.placeLine(): String? {
    val np = this ?: return null
    return listOfNotNull(np.fcc?.city, np.fcc?.state).joinToString(", ").takeIf { it.isNotBlank() }
}

private fun NowPlaying?.activeFavorite(): Favorite? {
    val np = this ?: return null
    val f = np.freq?.toDoubleOrNull() ?: return null
    return Favorites.SEED.firstOrNull { it.band == np.band && abs(it.freq - f) < 0.05 }
}
