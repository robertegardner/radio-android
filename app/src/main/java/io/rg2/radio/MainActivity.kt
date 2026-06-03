package io.rg2.radio

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import io.rg2.radio.ui.NowPlayingRoute
import io.rg2.radio.ui.theme.RadioTheme

/**
 * Single-activity host. Sets up the theme + now-playing screen and requests the
 * notification permission needed for the media notification on API 33+.
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
            RadioTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    NowPlayingRoute(Modifier.padding(padding))
                }
            }
        }
    }
}
