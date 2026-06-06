package io.rg2.radio.data

/**
 * Current-track artist/title, independent of how the backend derived them.
 * Prefers the discrete [NowPlaying.track] block, then the lyrics-matched
 * [Song], then discrete RDS fields. All may be null — talk content has no song,
 * and some FM RDS carries only a scrolling PS / station slogan. See docs/api.md.
 */
fun NowPlaying.trackTitle(): String? =
    track?.title?.takeIf { it.isNotBlank() }
        ?: lyrics?.song?.title?.takeIf { it.isNotBlank() }
        ?: rds?.title?.takeIf { it.isNotBlank() }

fun NowPlaying.trackArtist(): String? =
    track?.artist?.takeIf { it.isNotBlank() }
        ?: lyrics?.song?.artist?.takeIf { it.isNotBlank() }
        ?: rds?.artist?.takeIf { it.isNotBlank() }

/** Server-provided cover art URL (from [NowPlaying.track] or [Song]), or null. */
fun NowPlaying.coverArtUrl(): String? =
    track?.artUrl?.takeIf { it.isNotBlank() }
        ?: lyrics?.song?.artUrl?.takeIf { it.isNotBlank() }
