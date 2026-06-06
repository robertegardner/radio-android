package io.rg2.radio.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models + static catalogs for the EMS/ATC scanner backend
 * (`https://ems.rg2.io`). This is a *different* backend from the FM/AM radio:
 * one shared SDR that the operator switches between sources, rather than a
 * tune-and-keep-streaming broadcast receiver.
 *
 * Schemas verified against the live server and the `/listen` page's own JS
 * (2026-06-06):
 *  - `GET  /api/status`          — current job + upcoming NOAA passes
 *  - `POST /api/source/moswin`   — switch the SDR to MOSWIN P25 (empty body)
 *  - `POST /api/monitor/tune`    — tune aviation AM (body below)
 *  - `POST /api/monitor/squelch` — `{enabled}`
 *  - `GET  /api/calls?limit=N`   — recent MOSWIN call recordings
 *
 * Switching is asynchronous: after a control POST, poll `/api/status` until
 * `current.name` becomes the expected job, then let Icecast buffer before
 * playing. See [ScannerApi] / the ViewModel.
 */

// ---------------------------------------------------------------------------
// GET /api/status
// ---------------------------------------------------------------------------

@Serializable
data class ScannerStatus(
    val current: ScannerJob? = null,
    @SerialName("sdr_owner") val sdrOwner: String? = null,
    @SerialName("upcoming_passes") val upcomingPasses: List<SatPass> = emptyList(),
)

/**
 * The job currently holding the SDR. [name] is the scheduler job id —
 * [JOB_MOSWIN] when MOSWIN P25 is up, [JOB_MONITOR] when aviation is tuned, or
 * something else (e.g. a NOAA pass) when the scheduler has preempted both.
 * [detail] carries live context: `"active: <talkgroup>"` while a MOSWIN call is
 * up, `"monitoring control channel"` otherwise, or the freq for the monitor.
 */
@Serializable
data class ScannerJob(
    val name: String? = null,
    val detail: String? = null,
) {
    /** The live talkgroup if a MOSWIN call is in progress, else null. */
    val activeTalkgroup: String?
        get() = detail?.takeIf { it.startsWith("active:") }?.removePrefix("active:")?.trim()
}

/** An upcoming NOAA APT satellite pass — the scheduler may preempt the scanner for these. */
@Serializable
data class SatPass(
    val satellite: String? = null,
    @SerialName("freq_mhz") val freqMhz: Double? = null,
    /** ISO-8601 with offset, e.g. "2026-06-06T03:37:20.0+00:00". */
    val aos: String? = null,
    val los: String? = null,
    @SerialName("max_el") val maxEl: Double? = null,
)

// ---------------------------------------------------------------------------
// POST /api/monitor/tune  +  /api/monitor/squelch  +  control responses
// ---------------------------------------------------------------------------

/**
 * Aviation AM tune request. [freq] is a string with an optional unit suffix
 * (`"132.536M"`); the backend parses k/M/G. Mirrors the exact body the web
 * `/listen` page sends.
 */
@Serializable
data class MonitorTuneRequest(
    val freq: String,
    val mode: String = "am",
    val gain: Int = 40,
    val label: String? = null,
    @SerialName("duration_s") val durationS: Int = 3600,
    @SerialName("audio_squelch") val audioSquelch: Boolean = false,
)

@Serializable
data class SquelchRequest(val enabled: Boolean)

/**
 * Current squelch state from `GET /api/monitor/squelch`. [enabled] is the
 * configured intent (persisted across re-tunes; backend default is ON);
 * [activeOnMonitor] is whether the agate gate is applied to the live pipeline
 * right now. The POST returns the same shape.
 */
@Serializable
data class SquelchState(
    val enabled: Boolean = false,
    @SerialName("active_on_monitor") val activeOnMonitor: Boolean = false,
)

/** Control POSTs return `{}` on success or `{error: "..."}` on failure. */
@Serializable
data class ScannerControlResponse(val error: String? = null)

// ---------------------------------------------------------------------------
// GET /api/calls
// ---------------------------------------------------------------------------

/** One recorded MOSWIN call. Served back as audio at `<scannerBase>/recordings/ems/<path>`. */
@Serializable
data class ScannerCall(
    val ts: String? = null,
    val talkgroup: String? = null,
    val tgid: String? = null,
    val radio: String? = null,
    val filename: String? = null,
    val path: String? = null,
    @SerialName("size_kb") val sizeKb: Int? = null,
    val transcript: String? = null,
)

// ---------------------------------------------------------------------------
// Static catalogs (mirror the constants embedded in the /listen page JS)
// ---------------------------------------------------------------------------

/** The two SDR sources the scanner can present. */
enum class ScannerSource { MOSWIN, AVIATION }

/**
 * A MOSWIN sub-stream. Switching category just re-points the player at a
 * different Icecast mount — same SDRTrunk, no backend control call needed.
 */
data class MoswinCategory(val name: String, val slug: String, val url: String)

/** A confirmed-active Cape Girardeau air-band preset for one-tap aviation tuning. */
data class AviationPreset(
    val id: Int,
    val label: String,
    val freq: String,
    val gain: Int,
    val desc: String,
)

/**
 * Scanner backend constants. The Icecast mounts live on the same host as the
 * radio stream (`icecast.rg2.io`, HTTPS). Kept here (not in the repo's secrets)
 * because they're public read URLs, mirroring how the radio stream URL is a
 * constant in [RadioSettings].
 */
object ScannerCatalog {
    const val JOB_MOSWIN = "ems_scanner"
    const val JOB_MONITOR = "monitor"

    const val MOSWIN_DEFAULT_URL = "https://icecast.rg2.io/ems.mp3"
    const val MONITOR_URL = "https://icecast.rg2.io/monitor.mp3"

    /** MOSWIN category sub-streams; [0] = All. */
    val CATEGORIES: List<MoswinCategory> = listOf(
        MoswinCategory("All", "all", MOSWIN_DEFAULT_URL),
        MoswinCategory("Police", "police", "https://icecast.rg2.io/ems-police.mp3"),
        MoswinCategory("Fire", "fire", "https://icecast.rg2.io/ems-fire.mp3"),
        MoswinCategory("Interop", "interop", "https://icecast.rg2.io/ems-interop.mp3"),
    )

    /** Aviation presets — confirmed-active Cape Girardeau air-band channels. */
    val PRESETS: List<AviationPreset> = listOf(
        AviationPreset(1, "Memphis Ctr", "132.536M", 40, "132.536 · AM"),
        AviationPreset(2, "KCGI Tower", "125.525M", 40, "125.525 · AM"),
        AviationPreset(3, "Approach", "124.710M", 40, "124.710 · AM"),
        AviationPreset(4, "Center", "135.500M", 40, "135.500 · AM"),
        AviationPreset(5, "ARTCC", "127.490M", 40, "127.490 · AM"),
        AviationPreset(6, "ARTCC 2", "128.320M", 40, "128.320 · AM"),
    )
}
