package io.rg2.radio.wear

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import io.rg2.radio.wear.ui.RadioWearApp
import io.rg2.radio.wear.ui.theme.RadioWearTheme

/**
 * Single-activity Wear host. Requests the notification permission (API 33+) for
 * the media foreground notification, then shows the one-screen watch UI.
 */
class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            RadioWearTheme {
                RadioWearApp()
            }
        }
    }
}
