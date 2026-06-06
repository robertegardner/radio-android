package io.rg2.radio.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import io.rg2.radio.BuildConfig
import io.rg2.radio.data.AviationPreset
import io.rg2.radio.data.MoswinCategory
import io.rg2.radio.data.ScannerCall
import io.rg2.radio.data.ScannerCatalog
import io.rg2.radio.data.ScannerSource
import io.rg2.radio.data.ScannerStatus
import io.rg2.radio.ui.theme.Amber
import io.rg2.radio.ui.theme.SignalBad
import io.rg2.radio.ui.theme.SignalGood
import io.rg2.radio.ui.theme.SignalWarn
import kotlinx.coroutines.delay

// Source accents (match the web /listen page): MOSWIN green, aviation amber.
private val MoswinGreen = Color(0xFF6DF09B)
private val ScannerBlue = Color(0xFF7AA2F7)

// ---------------------------------------------------------------------------
// Route: connects the shared MediaController + ViewModel to the screen.
// ---------------------------------------------------------------------------

@Composable
fun ScannerRoute(
    modifier: Modifier = Modifier,
    viewModel: ScannerViewModel = viewModel(),
) {
    val controller = rememberMediaController()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val calls by viewModel.calls.collectAsStateWithLifecycle()

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

    // Route the ViewModel's playback intents into the shared media session.
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        viewModel.playback.collect { cmd ->
            when (cmd) {
                is ScannerPlayback.Play -> {
                    val item = MediaItem.Builder()
                        .setMediaId(cmd.mediaId)
                        .setRequestMetadata(
                            MediaItem.RequestMetadata.Builder()
                                .setMediaUri(Uri.parse(cmd.url))
                                .build(),
                        )
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(cmd.title)
                                .setStation(cmd.title)
                                .setArtist(cmd.subtitle)
                                .build(),
                        )
                        .build()
                    c.setMediaItem(item)
                    c.prepare()
                    c.play()
                }
                ScannerPlayback.Stop -> c.pause()
            }
        }
    }

    // MOSWIN: poll recent calls while shown. Aviation: sync the persisted squelch
    // state once so the toggle reflects the backend (default is ON).
    LaunchedEffect(ui.source) {
        if (ui.source == ScannerSource.MOSWIN) {
            while (true) {
                viewModel.refreshCalls()
                delay(CALL_REFRESH_MS)
            }
        } else {
            viewModel.refreshSquelchState()
        }
    }

    ScannerScreen(
        modifier = modifier,
        ui = ui,
        status = status,
        calls = calls,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        enabled = controller != null,
        onSelectMoswin = viewModel::selectMoswin,
        onSelectAviation = viewModel::selectAviationSource,
        onSelectCategory = viewModel::selectCategory,
        onTunePreset = viewModel::tunePreset,
        onDirectTune = viewModel::commitDirectTune,
        onToggleSquelch = viewModel::toggleSquelch,
        onPlayCall = viewModel::playCall,
        onPlayPause = {
            val c = controller ?: return@ScannerScreen
            when {
                c.currentMediaItem == null ->
                    if (ui.source == ScannerSource.MOSWIN) viewModel.selectMoswin()
                c.isPlaying -> c.pause()
                else -> c.play()
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Stateless screen.
// ---------------------------------------------------------------------------

@Composable
private fun ScannerScreen(
    ui: ScannerUiState,
    status: Polled<ScannerStatus>,
    calls: List<ScannerCall>,
    isPlaying: Boolean,
    isBuffering: Boolean,
    enabled: Boolean,
    onSelectMoswin: () -> Unit,
    onSelectAviation: () -> Unit,
    onSelectCategory: (MoswinCategory) -> Unit,
    onTunePreset: (AviationPreset) -> Unit,
    onDirectTune: (String, Int) -> Unit,
    onToggleSquelch: () -> Unit,
    onPlayCall: (ScannerCall) -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScannerHeader(status)
        SourceTabs(ui.source, enabled, onSelectMoswin, onSelectAviation)
        ScannerLcd(ui, status)

        when (ui.source) {
            ScannerSource.MOSWIN -> MoswinPanel(ui, calls, enabled, onSelectCategory, onPlayCall)
            ScannerSource.AVIATION -> AviationPanel(ui, enabled, onTunePreset, onDirectTune, onToggleSquelch)
        }

        TransportButton(
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            enabled = enabled,
            onClick = onPlayPause,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        if (ui.statusMessage.isNotBlank()) {
            Text(
                text = ui.statusMessage,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        NextPassHint(status)
    }
}

@Composable
private fun ScannerHeader(status: Polled<ScannerStatus>) {
    val (dot, label) = when {
        status.error != null -> SignalBad to "OFFLINE"
        status.data != null -> SignalGood to when (status.data.current?.name) {
            ScannerCatalog.JOB_MOSWIN -> "MOSWIN"
            ScannerCatalog.JOB_MONITOR -> "AVIATION"
            null -> "IDLE"
            else -> status.data.current?.name?.uppercase().orEmpty()
        }
        else -> SignalWarn to "CONNECTING"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(
                text = "RG2 SCANNER",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 4.sp,
            )
            Text(
                text = "EMS · ATC · v${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA}",
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
private fun SourceTabs(
    source: ScannerSource,
    enabled: Boolean,
    onSelectMoswin: () -> Unit,
    onSelectAviation: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SourceTab(
            title = "MOSWIN P25",
            sub = "EMS · PUBLIC SAFETY",
            active = source == ScannerSource.MOSWIN,
            accent = MoswinGreen,
            enabled = enabled,
            onClick = onSelectMoswin,
            modifier = Modifier.weight(1f),
        )
        SourceTab(
            title = "Aviation AM",
            sub = "ATC · AIR BAND",
            active = source == ScannerSource.AVIATION,
            accent = Amber,
            enabled = enabled,
            onClick = onSelectAviation,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SourceTab(
    title: String,
    sub: String,
    active: Boolean,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (active) accent else Color.Transparent
    Card(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .border(1.5.dp, border, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = if (active) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                color = if (active) accent else MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = sub,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun ScannerLcd(ui: ScannerUiState, status: Polled<ScannerStatus>) {
    val moswin = ui.source == ScannerSource.MOSWIN
    val accent = if (moswin) MoswinGreen else Amber
    val band = if (moswin) "P25" else "AM"
    val freq = if (moswin) "769.169" else (ui.aviationFreq?.let(::numericFreq) ?: "---.---")

    val liveJob = status.data?.current?.name
    val talkgroup = status.data?.current?.activeTalkgroup
    val callLine = when {
        moswin && talkgroup != null -> "▶ $talkgroup · talkgroup"
        moswin -> "MOSWIN control channel · monitoring"
        ui.aviationLabel != null -> "${ui.aviationLabel} · ${ui.aviationFreq}"
        else -> "Pick a preset or direct-tune"
    }
    val preempted = status.data != null && (
        (moswin && liveJob != null && liveJob != ScannerCatalog.JOB_MOSWIN) ||
            (!moswin && ui.activePresetId != null && liveJob != ScannerCatalog.JOB_MONITOR)
        )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(band, color = accent, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(7.dp).clip(CircleShape)
                    .background(if (preempted) SignalWarn else accent))
                Spacer(Modifier.width(6.dp))
                Text("STREAM", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp, letterSpacing = 1.sp)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(freq, color = accent, fontSize = 46.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text("MHz", color = accent, fontSize = 14.sp, modifier = Modifier.padding(bottom = 9.dp))
            }
            Text(
                text = if (preempted) "Preempted by ${liveJob?.uppercase()}" else callLine,
                color = if (preempted) SignalWarn else MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MoswinPanel(
    ui: ScannerUiState,
    calls: List<ScannerCall>,
    enabled: Boolean,
    onSelectCategory: (MoswinCategory) -> Unit,
    onPlayCall: (ScannerCall) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("CATEGORIES")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScannerCatalog.CATEGORIES.forEach { cat ->
                Chip(
                    text = cat.name,
                    active = ui.category.slug == cat.slug,
                    accent = MoswinGreen,
                    enabled = enabled,
                    onClick = { onSelectCategory(cat) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SectionLabel("RECENT CALLS")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                if (calls.isEmpty()) {
                    Text(
                        text = "No calls recorded yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    calls.forEach { call -> CallRow(call, enabled, onPlayCall) }
                }
            }
        }
    }
}

@Composable
private fun CallRow(call: ScannerCall, enabled: Boolean, onPlayCall: (ScannerCall) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onPlayCall(call) }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = call.ts?.replace('T', ' ')?.drop(5)?.take(11) ?: "",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = call.talkgroup ?: call.tgid ?: "—",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        call.radio?.let {
            Text("← $it", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AviationPanel(
    ui: ScannerUiState,
    enabled: Boolean,
    onTunePreset: (AviationPreset) -> Unit,
    onDirectTune: (String, Int) -> Unit,
    onToggleSquelch: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("PRESETS — TAP TO TUNE")
        // 6 presets in a 2-column grid.
        ScannerCatalog.PRESETS.chunked(2).forEach { rowPresets ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowPresets.forEach { p ->
                    PresetCard(
                        preset = p,
                        active = ui.activePresetId == p.id,
                        enabled = enabled,
                        onClick = { onTunePreset(p) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowPresets.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        SectionLabel("DIRECT TUNE")
        DirectTuneRow(enabled, onDirectTune)

        Chip(
            text = "Squelch: ${if (ui.squelchOn) "ON" else "OFF"}",
            active = ui.squelchOn,
            accent = ScannerBlue,
            enabled = enabled,
            onClick = onToggleSquelch,
        )
    }
}

@Composable
private fun PresetCard(
    preset: AviationPreset,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .border(1.dp, if (active) Amber else Color.Transparent, RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(
            containerColor = if (active) Amber.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(preset.label, color = if (active) Amber else MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(preset.desc, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DirectTuneRow(enabled: Boolean, onDirectTune: (String, Int) -> Unit) {
    var freq by rememberSaveable { mutableStateOf("") }
    var gain by rememberSaveable { mutableStateOf("40") }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = freq,
            onValueChange = { freq = it },
            label = { Text("Freq (e.g. 127.5M)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = gain,
            onValueChange = { gain = it.filter(Char::isDigit).take(3) },
            label = { Text("Gain") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(96.dp),
        )
        Chip(
            text = "GO",
            active = true,
            accent = Amber,
            enabled = enabled && freq.isNotBlank(),
            onClick = { onDirectTune(freq, gain.toIntOrNull() ?: 40) },
        )
    }
}

@Composable
private fun Chip(
    text: String,
    active: Boolean,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, if (active) accent else Color.Transparent, RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (active) accent else MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TransportButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isBuffering) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(28.dp),
            )
        } else {
            Text(
                text = if (isPlaying) "❚❚" else "▶",
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun NextPassHint(status: Polled<ScannerStatus>) {
    val pass = status.data?.upcomingPasses?.firstOrNull() ?: return
    val time = pass.aos?.substringAfter('T')?.take(5)?.let { "$it" + "Z" }
    Text(
        text = "Next NOAA pass: ${pass.satellite ?: "—"}${time?.let { " · $it" } ?: ""} (may preempt the scanner)",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp,
    )
}

/** Strip a trailing unit suffix (`132.536M` → `132.536`) for the LCD numeral. */
private fun numericFreq(raw: String): String = raw.takeWhile { it.isDigit() || it == '.' }

private const val CALL_REFRESH_MS = 5_000L
