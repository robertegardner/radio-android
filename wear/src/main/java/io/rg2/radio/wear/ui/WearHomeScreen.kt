package io.rg2.radio.wear.ui

import android.content.ComponentName
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import io.rg2.radio.data.Favorites
import io.rg2.radio.data.NowPlaying
import io.rg2.radio.data.ScannerCatalog
import io.rg2.radio.data.trackArtist
import io.rg2.radio.data.trackTitle
import io.rg2.radio.wear.BuildConfig
import io.rg2.radio.wear.playback.WearPlaybackService

/**
 * The whole watch UI: one scrolling [ScalingLazyColumn] with everything tappable
 * — now-playing + play/pause at the top, then Radio favorites, MOSWIN
 * categories, and Aviation presets. Tapping any source tunes/switches and plays
 * on the watch's own ExoPlayer.
 */
@Composable
fun RadioWearApp(vm: WearViewModel = viewModel()) {
    val controller = rememberWearController()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val scannerStatus by vm.scannerStatus.collectAsStateWithLifecycle()

    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose {}
        fun sync() { isPlaying = c.isPlaying }
        sync()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) = sync()
            override fun onPlaybackStateChanged(state: Int) = sync()
        }
        c.addListener(listener)
        onDispose { c.removeListener(listener) }
    }

    // Route ViewModel playback intents into the watch's media session.
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        vm.playback.collect { cmd ->
            val item = when (cmd) {
                is WearPlayback.PlayFavorite ->
                    MediaItem.Builder().setMediaId(cmd.favorite.mediaId).build()
                is WearPlayback.PlayStream ->
                    MediaItem.Builder()
                        .setMediaId(cmd.mediaId)
                        .setRequestMetadata(
                            MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(cmd.url)).build(),
                        )
                        .setMediaMetadata(
                            MediaMetadata.Builder().setTitle(cmd.title).setStation(cmd.title).build(),
                        )
                        .build()
            }
            c.setMediaItem(item)
            c.prepare()
            c.play()
        }
    }

    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxWidth(),
            state = listState,
        ) {
            item { NowPlayingHeader(nowPlaying, ui.message) }
            item {
                Button(
                    onClick = {
                        val c = controller ?: return@Button
                        if (c.currentMediaItem == null) return@Button
                        if (c.isPlaying) c.pause() else c.play()
                    },
                    enabled = controller != null,
                ) {
                    Text(if (isPlaying) "❚❚" else "▶")
                }
            }

            item { ListHeader { Text("RADIO") } }
            items(Favorites.SEED) { fav ->
                SourceChip(
                    label = fav.label,
                    secondary = fav.sublabel,
                    active = ui.activeMediaId == fav.mediaId,
                    enabled = controller != null,
                    onClick = { vm.playFavorite(fav) },
                )
            }

            item {
                ListHeader {
                    val tg = scannerStatus?.current?.activeTalkgroup
                    Text(if (tg != null) "MOSWIN · $tg" else "MOSWIN")
                }
            }
            items(ScannerCatalog.CATEGORIES) { cat ->
                val mediaId = WearPlaybackService.SCANNER_PREFIX + "moswin:" + cat.slug
                SourceChip(
                    label = cat.name,
                    secondary = "P25 scanner",
                    active = ui.activeMediaId == mediaId,
                    enabled = controller != null && !ui.busy,
                    onClick = { vm.selectMoswinCategory(cat) },
                )
            }

            item { ListHeader { Text("AVIATION") } }
            items(ScannerCatalog.PRESETS) { preset ->
                val mediaId = WearPlaybackService.SCANNER_PREFIX + "av:" + preset.freq
                SourceChip(
                    label = preset.label,
                    secondary = preset.desc,
                    active = ui.activeMediaId == mediaId,
                    enabled = controller != null && !ui.busy,
                    onClick = { vm.tunePreset(preset) },
                )
            }

            item {
                Text(
                    text = "v${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA}",
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.caption3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun NowPlayingHeader(np: NowPlaying?, message: String) {
    val station = np?.rds?.ps?.takeIf { it.isNotBlank() }
        ?: np?.fcc?.call?.takeIf { it.isNotBlank() }
    val title = np?.trackTitle()
    val artist = np?.trackArtist()
    val freq = np?.freq

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "RG2 RADIO",
            color = MaterialTheme.colors.primary,
            style = MaterialTheme.typography.title3,
        )
        val line = when {
            title != null && artist != null -> "$artist — $title"
            title != null -> title
            station != null && freq != null -> "$station · $freq"
            station != null -> station
            freq != null -> freq
            else -> "Pick a source below"
        }
        Text(
            text = if (message.isNotBlank()) message else line,
            color = MaterialTheme.colors.onBackground,
            style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SourceChip(
    label: String,
    secondary: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Chip(
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = { Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = onClick,
        enabled = enabled,
        colors = if (active) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun rememberWearController(): MediaController? {
    val context = androidx.compose.ui.platform.LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, WearPlaybackService::class.java))
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
