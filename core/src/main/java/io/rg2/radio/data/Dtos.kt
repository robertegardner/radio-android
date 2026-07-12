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

    /** FM stereo decode is *selected* (the mono/stereo toggle), not signal state. */
    val stereo: Boolean = false,
    /** True 19 kHz pilot lock — the backend gates this on FM + stereo mode + a fresh pilot. */
    val pilot: Boolean = false,
    @SerialName("pilot_rms") val pilotRms: Double? = null,
    /** 0..1 mono→stereo blend the decoder is currently applying. */
    @SerialName("pilot_blend") val pilotBlend: Double? = null,
    /** Active antenna port — one of [Antennas.ALL] (e.g. "Antenna A", "HF+"). */
    val antenna: String? = null,

    val fcc: Fcc? = null,
    val rds: Rds? = null,
    val caption: Caption? = null,
    val lyrics: Lyrics? = null,
    /**
     * Discrete now-playing track (artist/title/album/art_url) when the backend
     * has identified the song via RDS or AcoustID; null for talk/unidentified.
     * Prefer this over [Lyrics.song]/[Rds] for display — see [trackTitle] etc.
     */
    val track: Song? = null,
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
    val album: String? = null,
    /** Cover art URL fetched server-side (iTunes), or null. */
    @SerialName("art_url") val artUrl: String? = null,
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
    /** Best antenna from the multi-antenna scan sweep (e.g. "Antenna B", "HF+"). */
    val antenna: String? = null,
    /** SNR in dB per antenna key ("A"/"B"/"C"/"HF+") from that sweep. */
    @SerialName("by_antenna") val byAntenna: Map<String, Double> = emptyMap(),
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
 * [stereo]/[antenna] are optional per-tune audio settings the backend applies
 * in the same stream restart; null keys are omitted from the JSON, which the
 * backend reads as "leave the current setting alone".
 */
@Serializable
data class TuneRequest(
    val freq: Double,
    val band: Band,
    val hd: Boolean = false,
    val subchannel: Int = 0,
    val stereo: Boolean? = null,
    val antenna: String? = null,
)

/** Response shape shared by /api/tune, /api/stereo, /api/antenna, /api/bitrate. */
@Serializable
data class TuneResponse(
    val ok: Boolean = false,
    val error: String? = null,
)

// ---------------------------------------------------------------------------
// POST /api/stereo · /api/antenna · /api/bitrate
//   Each persists the setting and restarts the stream (client-side that looks
//   like a tune: a brief drop the reconnect logic already rides out).
// ---------------------------------------------------------------------------

@Serializable
data class StereoRequest(val stereo: Boolean)

@Serializable
data class AntennaRequest(val antenna: String)

@Serializable
data class BitrateRequest(val bitrate: String)

/**
 * Antenna ports the backend accepts — the wire values are the display strings.
 * A/B/C are the dx-R2's ports (A = the FM antenna, B = AM loop, C = long-wire);
 * "HF+" is a separate AM-only device (Airspy HF+ on the YouLoop) — FM always
 * runs on the dx-R2, so HF+ must not be offered while tuned to FM.
 */
object Antennas {
    const val HF_PLUS = "HF+"
    val ALL: List<String> = listOf("Antenna A", "Antenna B", "Antenna C", HF_PLUS)

    /** Short chip label: "Antenna B" → "B", "HF+" → "HF+". */
    fun shortLabel(antenna: String): String = antenna.removePrefix("Antenna ").trim()
}

// ---------------------------------------------------------------------------
// GET /api/status
// ---------------------------------------------------------------------------

@Serializable
data class Status(
    @SerialName("current_band") val currentBand: Band? = null,
    @SerialName("current_freq") val currentFreq: String? = null,
    @SerialName("current_hd") val currentHd: Boolean = false,
    @SerialName("current_subchannel") val currentSubchannel: Int = 0,
    /** Stream MP3 bitrate, e.g. "256k". */
    val bitrate: String? = null,
    /** e.g. "active". */
    val status: String? = null,
)
