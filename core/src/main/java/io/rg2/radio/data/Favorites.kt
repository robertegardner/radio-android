package io.rg2.radio.data

/**
 * A preset station the user can tune to. The backend always serves a single
 * Icecast stream URL; "playing a favorite" means POSTing its [freq]/[band] to
 * `/api/tune` and listening to that one stream (see [io.rg2.radio.playback]).
 *
 * [freq] matches the units `/api/tune` expects: MHz for FM, kHz for AM.
 */
data class Favorite(
    val band: Band,
    val freq: Double,
    val label: String,
    val sublabel: String,
) {
    /** Stable id used as the Media3 mediaId in the browse tree. */
    val mediaId: String get() = "$MEDIA_ID_PREFIX${band.name}/$freq"

    companion object {
        const val MEDIA_ID_PREFIX = "fav/"

        /**
         * Parse a favorite mediaId back into its band/freq, or null if it isn't
         * one. Seed presets resolve to their full entry; station-browser and
         * Android Auto station items reuse the same "fav/BAND/freq" scheme for
         * arbitrary scanned stations, so unknown ids parse into a synthetic
         * favorite (generic label — the live metadata pump names it within ~1s).
         */
        fun fromMediaId(mediaId: String?): Favorite? {
            if (mediaId == null || !mediaId.startsWith(MEDIA_ID_PREFIX)) return null
            Favorites.byMediaId(mediaId)?.let { return it }
            val parts = mediaId.removePrefix(MEDIA_ID_PREFIX).split("/")
            if (parts.size != 2) return null
            val band = Band.entries.firstOrNull { it.name == parts[0] } ?: return null
            val freq = parts[1].toDoubleOrNull() ?: return null
            // Reject garbage ids (stale controllers, NaN, out-of-band values)
            // instead of tuning the backend to an arbitrary frequency.
            val plausible = when (band) {
                Band.FM -> freq in 76.0..108.0   // MHz
                Band.AM -> freq in 520.0..1710.0 // kHz
            }
            if (!plausible) return null
            return Favorite(band, freq, "${formatFreq(band, freq)} ${band.name}", "")
        }

        /** "100.7" for FM MHz, "1120" for AM kHz. */
        fun formatFreq(band: Band, freq: Double): String =
            if (band == Band.AM) freq.toInt().toString() else freq.toString()
    }
}

/**
 * Bridge a scanned [Station] to the favorite/tune model — the station browser
 * and the Android Auto stations folder tune through the same mediaId path as
 * the seed presets.
 */
fun Station.toFavorite(): Favorite = Favorite(
    band = band,
    freq = tuneFreq,
    label = call?.let { "$it ${Favorite.formatFreq(band, tuneFreq)}" }
        ?: label
        ?: "${Favorite.formatFreq(band, tuneFreq)} ${band.name}",
    sublabel = listOfNotNull(
        city?.takeIf { it.isNotBlank() },
        state?.takeIf { it.isNotBlank() },
    ).joinToString(", "),
)

/**
 * Seed presets — Cardinals first (the whole reason this app is native). Call
 * signs/freqs confirmed against `/api/stations` where present; scans are
 * geography/time dependent, so a favorite need not appear in every scan.
 */
object Favorites {
    const val ROOT_ID = "root"

    /**
     * mediaId for "play whatever the backend is currently tuned to" — the live
     * Icecast stream without issuing a tune. Lets Play work from a cold start.
     */
    const val LIVE_ID = "live"

    val SEED: List<Favorite> = listOf(
        Favorite(Band.AM, 1120.0, "KMOX 1120", "St. Louis · Cardinals flagship"),
        Favorite(Band.AM, 1230.0, "KZYM 1230", "Cape Girardeau · Cardinals affiliate"),
        Favorite(Band.FM, 100.7, "KGMO 100.7", "Cape Girardeau · local FM"),
        Favorite(Band.FM, 95.7, "95.7 FM", "Cardinals affiliate"),
    )

    fun byMediaId(mediaId: String): Favorite? = SEED.firstOrNull { it.mediaId == mediaId }
}
