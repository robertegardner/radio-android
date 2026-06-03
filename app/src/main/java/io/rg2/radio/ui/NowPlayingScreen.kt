package io.rg2.radio.ui

import android.content.ComponentName
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.rg2.radio.data.Band
import io.rg2.radio.data.Favorite
import io.rg2.radio.data.Favorites
import io.rg2.radio.data.NowPlaying
import io.rg2.radio.playback.PlaybackService
import io.rg2.radio.ui.theme.Amber
import io.rg2.radio.ui.theme.SignalBad
import io.rg2.radio.ui.theme.SignalGood
import io.rg2.radio.ui.theme.SignalWarn
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

    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose {}
        fun sync() {
            isPlaying = c.isPlaying
            isBuffering = c.playbackState == Player.STATE_BUFFERING
        }
        sync()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) = sync()
            override fun onPlaybackStateChanged(state: Int) = sync()
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
        TunerDisplay(np)
        ProgramPane(np)
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
        Text(
            text = "RG2 RADIO",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 4.sp,
        )
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
private fun TunerDisplay(np: NowPlaying?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BandChip(np?.band)

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
            np.fccLine()?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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

/** Captions for talk (Cardinals play-by-play), else song info for music. */
@Composable
private fun ProgramPane(np: NowPlaying?) {
    np ?: return
    val caption = np.caption?.text?.takeIf { np.mode == "captions" && it.isNotBlank() }
    when {
        caption != null -> InfoCard {
            SectionLabel("LIVE CAPTIONS")
            Text(
                text = caption,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 17.sp,
                lineHeight = 24.sp,
            )
        }

        np.songLine() != null -> InfoCard {
            SectionLabel("NOW PLAYING")
            Text(
                text = np.lyrics?.song?.title ?: np.songLine()!!,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            np.lyrics?.song?.artist?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
            }
            np.currentLyric()?.let {
                Text(text = it, color = Amber, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
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
private fun rememberMediaController(): MediaController? {
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
    return np.rds?.ps?.takeIf { it.isNotBlank() }
        ?: np.fcc?.call?.takeIf { it.isNotBlank() }
        ?: "—"
}

private fun NowPlaying?.fccLine(): String? {
    val np = this ?: return null
    val parts = listOfNotNull(np.fcc?.call, np.fcc?.city, np.fcc?.state)
    if (parts.isEmpty()) return np.rds?.rt?.takeIf { it.isNotBlank() }
    val place = listOfNotNull(np.fcc?.city, np.fcc?.state).joinToString(", ")
    return listOfNotNull(np.fcc?.call, place.takeIf { it.isNotBlank() }).joinToString(" · ")
}

private fun NowPlaying.songLine(): String? {
    val song = lyrics?.song ?: return null
    val artist = song.artist?.takeIf { it.isNotBlank() }
    val title = song.title?.takeIf { it.isNotBlank() }
    return when {
        artist != null && title != null -> "$artist — $title"
        else -> title ?: artist
    }
}

private fun NowPlaying.currentLyric(): String? {
    val l = lyrics ?: return null
    return l.lines.getOrNull(l.index)?.text?.takeIf { it.isNotBlank() }
}

private fun NowPlaying?.activeFavorite(): Favorite? {
    val np = this ?: return null
    val f = np.freq?.toDoubleOrNull() ?: return null
    return Favorites.SEED.firstOrNull { it.band == np.band && abs(it.freq - f) < 0.05 }
}
