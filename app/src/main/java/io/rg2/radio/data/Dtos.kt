package io.rg2.radio.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the radio backend. These mirror the *real* JSON observed on
 * the live server (see `docs/api.md`), not the original CLAUDE.md guesses.
 *
 * Conventions:
 *  - Every nested object is nullable: `caption`, `lyrics`, `lyrics.song`, and
 *    `fcc` come and go depending on band and state.
 *  - Unknown/extra fields are ignored by the [Json] config in RadioApi, so the
 *    backend can add fields without breaking us.
 *  - `hd_*` flags are modeled for completeness but unused — HD is dead on this
 *    backend, and there is no HD UI.
 */

/** Tuning band. The backend speaks `"fm"` / `"am"` (lowercase, never "wbfm"). */
@Serializable
enum class Band {
    @SerialName("fm") FM,
    @SerialName("am") AM,
}

// ---------------------------------------------------------------------------
// GET /api/now_playing
// ---------------------------------------------------------------------------

@Serializable
data class NowPlaying(
    val available: Boolean = false,
    val band: Band? = null,
    val freq: String? = null,
    /** "lyrics" | "captions" | "idle" — selects the secondary pane. */
    val mode: String? = null,
    val subchannel: Int = 0,

    val hd: Boolean = false,
    @SerialName("hd_locked") val hdLocked: Boolean = false,
    @SerialName("hd_probing") val hdProbing: Boolean = false,
    @SerialName("hd_unavailable") val hdUnavailable: Boolean = false,

    val fcc: Fcc? = null,
    val rds: Rds? = null,
    val caption: Caption? = null,
    val lyrics: Lyrics? = null,
)

@Serializable
data class Fcc(
    val call: String? = null,
    val city: String? = null,
    val state: String? = null,
)

@Serializable
data class Rds(
    /** Program Service name — the short station name (e.g. "Ozzy"). */
    val ps: String? = null,
    /** RadioText. */
    val rt: String? = null,
    /** Often null — prefer [Song.artist]/[Song.title] for the playing track. */
    val artist: String? = null,
    val title: String? = null,
    val pi: String? = null,
    @SerialName("prog_type") val progType: String? = null,
    @SerialName("freq_mhz") val freqMhz: String? = null,
    @SerialName("last_update") val lastUpdate: Double? = null,
    @SerialName("started_at") val startedAt: Double? = null,
)

/** Whisper live captions — the relevant feature for Cardinals play-by-play. */
@Serializable
data class Caption(
    val text: String? = null,
    @SerialName("age_s") val ageSeconds: Double? = null,
    val updated: Double? = null,
)

/** LRClib synced lyrics (music FM). */
@Serializable
data class Lyrics(
    /** Index into [lines] of the currently-active line, or -1/absent. */
    val index: Int = -1,
    val lines: List<LyricLine> = emptyList(),
    val song: Song? = null,
)

@Serializable
data class LyricLine(
    val text: String = "",
    @SerialName("time_ms") val timeMs: Long = 0,
)

@Serializable
data class Song(
    val artist: String? = null,
    val title: String? = null,
    /** Seconds. */
    val duration: Double? = null,
    val source: String? = null,
    val score: Double? = null,
    @SerialName("matched_at") val matchedAt: Double? = null,
)

// ---------------------------------------------------------------------------
// GET /api/stations
// ---------------------------------------------------------------------------

@Serializable
data class Stations(
    val fm: List<Station> = emptyList(),
    val am: List<Station> = emptyList(),
    @SerialName("fm_scanned_at") val fmScannedAt: String? = null,
    @SerialName("am_scanned_at") val amScannedAt: String? = null,
)

/**
 * One scanned station. AM entries carry [freqKhz]; FM entries carry [freqMhz]
 * — exactly one is populated. Use [tuneFreq]/[band] to bridge to a tune request.
 */
@Serializable
data class Station(
    val call: String? = null,
    val city: String? = null,
    val state: String? = null,
    @SerialName("freq_mhz") val freqMhz: Double? = null,
    @SerialName("freq_khz") val freqKhz: Int? = null,
    val label: String? = null,
    @SerialName("power_db") val powerDb: Double? = null,
    @SerialName("snr_db") val snrDb: Double? = null,
) {
    /** Inferred band: AM if a kHz freq is present, otherwise FM. */
    val band: Band get() = if (freqKhz != null) Band.AM else Band.FM

    /** The numeric frequency to send to /api/tune (MHz for FM, kHz for AM). */
    val tuneFreq: Double get() = freqMhz ?: freqKhz?.toDouble() ?: 0.0
}

// ---------------------------------------------------------------------------
// POST /api/tune
// ---------------------------------------------------------------------------

/**
 * Tune request body. Key is `band` (not `mode`). `hd`/`subchannel` are always
 * false/0 on this backend but sent to match the web UI's exact payload.
 */
@Serializable
data class TuneRequest(
    val freq: Double,
    val band: Band,
    val hd: Boolean = false,
    val subchannel: Int = 0,
)

@Serializable
data class TuneResponse(
    val ok: Boolean = false,
    val error: String? = null,
)

// ---------------------------------------------------------------------------
// GET /api/status
// ---------------------------------------------------------------------------

@Serializable
data class Status(
    @SerialName("current_band") val currentBand: Band? = null,
    @SerialName("current_freq") val currentFreq: String? = null,
    @SerialName("current_hd") val currentHd: Boolean = false,
    @SerialName("current_subchannel") val currentSubchannel: Int = 0,
    /** e.g. "active". */
    val status: String? = null,
)
