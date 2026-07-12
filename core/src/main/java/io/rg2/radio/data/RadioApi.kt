package io.rg2.radio.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown for transport failures and unexpected (non-handled) HTTP statuses. */
class RadioApiException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The radio backend HTTP client. OkHttp + kotlinx.serialization (house style —
 * no Retrofit). All endpoints and their JSON shapes are documented in
 * `docs/api.md`; this class is the single place the app talks to the backend.
 *
 * Read endpoints ([nowPlaying], [stations], [status]) are public. The write
 * endpoint ([tune]) attaches an `Authorization` header when [RadioSettings]
 * supplies one — harmless today (auth isn't enforced yet) and correct once
 * NPMplus basic auth lands.
 *
 * [settings] is read on every call so live base-URL/credential changes from the
 * settings screen take effect without rebuilding the client.
 */
class RadioApi(
    private val settings: () -> RadioSettings,
    private val client: OkHttpClient = defaultClient(),
) {
    private val json = Json {
        ignoreUnknownKeys = true   // backend may add fields; don't break
        isLenient = true
        coerceInputValues = true   // null/absent -> declared defaults
    }

    suspend fun nowPlaying(): NowPlaying =
        getJson("/api/now_playing", NowPlaying.serializer())

    suspend fun stations(): Stations =
        getJson("/api/stations", Stations.serializer())

    suspend fun status(): Status =
        getJson("/api/status", Status.serializer())

    /**
     * POST a tune request. Returns the parsed [TuneResponse] for both success
     * (200 `{ok:true}`) and the backend's validation failures (400
     * `{ok:false,error:...}`); only transport/other errors throw.
     */
    suspend fun tune(request: TuneRequest): TuneResponse =
        postJson("/api/tune", json.encodeToString(TuneRequest.serializer(), request))

    /** Toggle FM stereo vs mono decode. Restarts the stream. */
    suspend fun setStereo(on: Boolean): TuneResponse =
        postJson("/api/stereo", json.encodeToString(StereoRequest.serializer(), StereoRequest(on)))

    /** Select the antenna port (a value from [Antennas.ALL]). Restarts the stream. */
    suspend fun setAntenna(antenna: String): TuneResponse =
        postJson("/api/antenna", json.encodeToString(AntennaRequest.serializer(), AntennaRequest(antenna)))

    /** Set the stream MP3 bitrate (e.g. "128k", "256k"). Restarts the stream. */
    suspend fun setBitrate(bitrate: String): TuneResponse =
        postJson("/api/bitrate", json.encodeToString(BitrateRequest.serializer(), BitrateRequest(bitrate)))

    /**
     * Shared write-endpoint POST. All four write endpoints speak the same
     * `{ok, error}` envelope for 200 and validation-failure 400; anything else
     * throws. Attaches the Authorization header when settings supply one.
     */
    private suspend fun postJson(path: String, bodyJson: String): TuneResponse {
        val s = settings()
        val builder = Request.Builder()
            .url(s.baseUrl.trimEnd('/') + path)
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
        s.authHeader?.let { builder.header("Authorization", it) }

        val resp = client.newCall(builder.build()).await()
        resp.use {
            val text = it.body?.string().orEmpty()
            if (it.isSuccessful || it.code == 400) {
                return decode(text, TuneResponse.serializer())
            }
            throw RadioApiException("POST $path failed: HTTP ${it.code} ${it.message}")
        }
    }

    private suspend fun <T> getJson(
        path: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T {
        val url = settings().baseUrl.trimEnd('/') + path
        val resp = client.newCall(Request.Builder().url(url).get().build()).await()
        resp.use {
            if (!it.isSuccessful) {
                throw RadioApiException("GET $path failed: HTTP ${it.code} ${it.message}")
            }
            return decode(it.body?.string().orEmpty(), deserializer)
        }
    }

    private suspend fun <T> decode(
        text: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.Default) {
        try {
            json.decodeFromString(deserializer, text)
        } catch (e: Exception) {
            throw RadioApiException("malformed JSON response", e)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // now_playing/status are polled ~1s; keep read timeout tight so a
            // stalled poll fails fast rather than piling up.
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
