package io.rg2.radio

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.rg2.radio.data.Favorite
import io.rg2.radio.data.Favorites
import io.rg2.radio.data.NowPlaying
import io.rg2.radio.playback.PlaybackService
import io.rg2.radio.ui.NowPlayingViewModel
import io.rg2.radio.ui.Polled

/**
 * Single-activity host. The screen now shows live now-playing metadata polled
 * from the backend (station / song / captions) alongside play/pause and the
 * seed favorites. Still a work-in-progress UI — the full tuner / station
 * browser comes later.
 */
class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    PlaybackScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun PlaybackScreen(
    modifier: Modifier = Modifier,
    viewModel: NowPlayingViewModel = viewModel(),
) {
    val controller = rememberMediaController()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()

    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose {}
        isPlaying = c.isPlaying
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        c.addListener(listener)
        onDispose { c.removeListener(listener) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Radio", style = MaterialTheme.typography.headlineMedium)

        NowPlayingCard(nowPlaying)

        Button(
            onClick = {
                val c = controller ?: return@Button
                if (c.isPlaying) c.pause() else c.play()
            },
            enabled = controller != null,
        ) {
            Text(if (isPlaying) "Pause" else "Play")
        }

        Text(text = "Favorites", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Favorites.SEED) { fav ->
                FavoriteRow(fav, enabled = controller != null) {
                    val c = controller ?: return@FavoriteRow
                    c.setMediaItem(MediaItem.Builder().setMediaId(fav.mediaId).build())
                    c.prepare()
                    c.play()
                }
            }
        }
    }
}

@Composable
private fun NowPlayingCard(state: Polled<NowPlaying>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val np = state.data
            when {
                np == null && state.error != null ->
                    Text("Offline — ${state.error}", style = MaterialTheme.typography.bodyMedium)
                np == null ->
                    Text("Connecting…", style = MaterialTheme.typography.bodyMedium)
                else -> NowPlayingDetails(np)
            }
        }
    }
}

@Composable
private fun NowPlayingDetails(np: NowPlaying) {
    Text(
        text = stationName(np),
        style = MaterialTheme.typography.titleLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    tunedLine(np)?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium)
    }
    songLine(np)?.let {
        Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    // Whisper captions — the relevant feature for Cardinals play-by-play.
    if (np.mode == "captions") {
        np.caption?.text?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun stationName(np: NowPlaying): String =
    np.rds?.ps?.takeIf { it.isNotBlank() }
        ?: np.fcc?.call?.takeIf { it.isNotBlank() }
        ?: "—"

private fun tunedLine(np: NowPlaying): String? {
    val band = np.band?.name ?: return np.freq
    val freq = np.freq ?: return band
    val place = listOfNotNull(np.fcc?.city, np.fcc?.state).joinToString(", ").takeIf { it.isNotBlank() }
    return buildString {
        append("$band $freq")
        if (place != null) append(" · $place")
    }
}

private fun songLine(np: NowPlaying): String? {
    val song = np.lyrics?.song ?: return null
    val artist = song.artist?.takeIf { it.isNotBlank() }
    val title = song.title?.takeIf { it.isNotBlank() }
    return when {
        artist != null && title != null -> "$artist — $title"
        else -> title ?: artist
    }
}

@Composable
private fun FavoriteRow(fav: Favorite, enabled: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = fav.label, style = MaterialTheme.typography.titleMedium)
            Text(text = fav.sublabel, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onClick, enabled = enabled) { Text("Tune & play") }
        }
    }
}

/**
 * Builds a [MediaController] bound to [PlaybackService] and releases it when the
 * composable leaves the tree. Returns null until the async connection completes.
 */
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
