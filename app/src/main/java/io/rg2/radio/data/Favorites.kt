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

        /** Parse a favorite mediaId back into its band/freq, or null if it isn't one. */
        fun fromMediaId(mediaId: String?): Favorite? {
            if (mediaId == null || !mediaId.startsWith(MEDIA_ID_PREFIX)) return null
            return Favorites.byMediaId(mediaId)
        }
    }
}

/**
 * Seed presets — Cardinals first (the whole reason this app is native). Call
 * signs/freqs confirmed against `/api/stations` where present; scans are
 * geography/time dependent, so a favorite need not appear in every scan.
 */
object Favorites {
    const val ROOT_ID = "root"

    val SEED: List<Favorite> = listOf(
        Favorite(Band.AM, 1120.0, "KMOX 1120", "St. Louis · Cardinals flagship"),
        Favorite(Band.AM, 1230.0, "KZYM 1230", "Cape Girardeau · Cardinals affiliate"),
        Favorite(Band.FM, 100.7, "KGMO 100.7", "Cape Girardeau · local FM"),
        Favorite(Band.FM, 95.7, "95.7 FM", "Cardinals affiliate"),
    )

    fun byMediaId(mediaId: String): Favorite? = SEED.firstOrNull { it.mediaId == mediaId }
}
