package io.rg2.radio.data

/**
 * Current-track artist/title, independent of how the backend derived them.
 * Prefers the matched [Song] (LRClib/identification) and falls back to discrete
 * RDS fields. Both are frequently null on this backend today — talk content has
 * no song, and FM RDS often carries only a scrolling PS / station slogan rather
 * than clean song metadata. See docs/api.md and the README notes.
 */
fun NowPlaying.trackTitle(): String? =
    lyrics?.song?.title?.takeIf { it.isNotBlank() }
        ?: rds?.title?.takeIf { it.isNotBlank() }

fun NowPlaying.trackArtist(): String? =
    lyrics?.song?.artist?.takeIf { it.isNotBlank() }
        ?: rds?.artist?.takeIf { it.isNotBlank() }
