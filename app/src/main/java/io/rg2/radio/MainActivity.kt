package io.rg2.radio

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.rg2.radio.ui.NowPlayingRoute
import io.rg2.radio.ui.ScannerRoute
import io.rg2.radio.ui.theme.RadioTheme

/**
 * Single-activity host. Two top-level destinations — the FM/AM [NowPlayingRoute]
 * and the EMS/ATC [ScannerRoute] — switched by a bottom tab bar. Both drive the
 * same Media3 session, so only one audio source plays at a time (selecting a
 * scanner stream takes over from the radio, and vice versa). A lightweight
 * state switch keeps us off a navigation library (house style: minimal deps).
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
                var tab by rememberSaveable { mutableStateOf(Tab.RADIO) }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = { TabBar(tab) { tab = it } },
                ) { padding ->
                    when (tab) {
                        Tab.RADIO -> NowPlayingRoute(Modifier.padding(padding))
                        Tab.SCANNER -> ScannerRoute(Modifier.padding(padding))
                    }
                }
            }
        }
    }
}

private enum class Tab(val label: String) { RADIO("RADIO"), SCANNER("SCANNER") }

@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .height(56.dp),
    ) {
        Tab.entries.forEach { tab ->
            val active = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable { onSelect(tab) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tab.label,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    letterSpacing = 2.sp,
                )
            }
        }
    }
}
