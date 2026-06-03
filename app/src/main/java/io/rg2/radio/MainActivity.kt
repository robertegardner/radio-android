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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.rg2.radio.data.Favorite
import io.rg2.radio.data.Favorites
import io.rg2.radio.playback.PlaybackService

/**
 * Single-activity host. For now this is a minimal, device-testable harness for
 * the playback layer — play/pause plus the seed favorites. The real tuner /
 * now-playing UI (polling `/api/now_playing`) replaces it later.
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
private fun PlaybackScreen(modifier: Modifier = Modifier) {
    val controller = rememberMediaController()

    var isPlaying by remember { mutableStateOf(false) }
    var nowTitle by remember { mutableStateOf<String?>(null) }

    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose {}
        isPlaying = c.isPlaying
        nowTitle = c.mediaMetadata.title?.toString()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                nowTitle = mediaMetadata.title?.toString()
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
        Text(text = nowTitle ?: "Nothing playing", style = MaterialTheme.typography.bodyLarge)

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
