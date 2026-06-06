package io.rg2.radio.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Looks up album/cover art for the currently-playing track.
 *
 * The radio backend doesn't expose artwork, so we query the public **iTunes
 * Search API** by artist + title (no key required). Only the song's
 * artist/title — the same data we already parse for lyrics — leaves the device,
 * and only for music; talk content has no song and is never looked up.
 *
 * Results (including misses) are cached in-memory by track so repeated polls of
 * the same song don't re-hit the network.
 */
class ArtworkRepository(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    // value "" = looked up, no art found (negative cache).
    private val cache = ConcurrentHashMap<String, String>()

    suspend fun artworkUrl(artist: String, title: String): String? {
        val key = "$artist|$title"
        cache[key]?.let { return it.ifEmpty { null } }

        val url = HttpUrl.Builder()
            .scheme("https")
            .host("itunes.apple.com")
            .addPathSegment("search")
            .addQueryParameter("term", "$artist $title")
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "1")
            .build()

        val result = runCatching {
            withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    val parsed = json.decodeFromString(ItunesSearchResult.serializer(), body)
                    // iTunes returns a 100x100 thumbnail; bump to a usable size.
                    parsed.results.firstOrNull()?.artworkUrl100
                        ?.replace("100x100bb", "600x600bb")
                }
            }
        }.getOrNull()

        cache[key] = result.orEmpty()
        return result
    }
}

@Serializable
private data class ItunesSearchResult(
    val results: List<ItunesTrack> = emptyList(),
)

@Serializable
private data class ItunesTrack(
    @SerialName("artworkUrl100") val artworkUrl100: String? = null,
)
