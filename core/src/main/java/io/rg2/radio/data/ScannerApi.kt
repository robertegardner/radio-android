package io.rg2.radio.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * HTTP client for the EMS/ATC scanner backend (`https://ems.rg2.io`). Same house
 * style as [RadioApi] — OkHttp + kotlinx.serialization, no Retrofit — and shares
 * the app-wide [OkHttpClient] so there's one connection pool.
 *
 * The scanner has a different control model from the radio: one shared SDR you
 * switch between sources. The read endpoint ([status], [calls]) is public; the
 * control endpoints ([selectMoswin], [tuneMonitor], [setSquelch]) are writes —
 * they attach the same optional `Authorization` header for when NPMplus auth
 * lands, harmless until then.
 *
 * [settings] is read on every call so a live base-URL change (settings screen,
 * later) takes effect without rebuilding the client.
 */
class ScannerApi(
    private val settings: () -> RadioSettings,
    private val client: OkHttpClient = RadioApi.defaultClient(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun status(): ScannerStatus =
        getJson("/api/status", ScannerStatus.serializer())

    suspend fun calls(limit: Int = 15): List<ScannerCall> =
        getJson("/api/calls?limit=$limit", ListSerializer(ScannerCall.serializer()))

    /** Switch the shared SDR to MOSWIN P25 (the EMS/public-safety default). Empty body. */
    suspend fun selectMoswin(): ScannerControlResponse =
        post("/api/source/moswin", body = null)

    /** Tune the aviation AM monitor. */
    suspend fun tuneMonitor(request: MonitorTuneRequest): ScannerControlResponse =
        post("/api/monitor/tune", json.encodeToString(MonitorTuneRequest.serializer(), request))

    /** Current squelch state (persisted; backend default is ON). */
    suspend fun squelchState(): SquelchState =
        getJson("/api/monitor/squelch", SquelchState.serializer())

    /**
     * Toggle the aviation-monitor audio squelch (an ffmpeg `agate` gate). The
     * backend restarts the rtl_fm→ffmpeg pipeline (~1-2s stream gap) and echoes
     * the new authoritative state, which we return.
     */
    suspend fun setSquelch(enabled: Boolean): SquelchState {
        val s = settings()
        val body = json.encodeToString(SquelchRequest.serializer(), SquelchRequest(enabled))
            .toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder().url(scannerBase() + "/api/monitor/squelch").post(body)
        s.authHeader?.let { builder.header("Authorization", it) }

        val resp = client.newCall(builder.build()).await()
        resp.use {
            if (!it.isSuccessful) {
                throw RadioApiException("squelch failed: HTTP ${it.code} ${it.message}")
            }
            return decode(it.body?.string().orEmpty(), SquelchState.serializer())
        }
    }

    /** Absolute URL of a recorded call's audio for the player. */
    fun recordingUrl(call: ScannerCall): String? {
        val p = call.path ?: call.filename ?: return null
        return scannerBase() + "/recordings/ems/" + p
    }

    private fun scannerBase(): String = settings().scannerBaseUrl.trimEnd('/')

    private suspend fun <T> getJson(
        path: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T {
        val resp = client.newCall(Request.Builder().url(scannerBase() + path).get().build()).await()
        resp.use {
            if (!it.isSuccessful) {
                throw RadioApiException("GET $path failed: HTTP ${it.code} ${it.message}")
            }
            return decode(it.body?.string().orEmpty(), deserializer)
        }
    }

    private suspend fun post(path: String, body: String?): ScannerControlResponse {
        val s = settings()
        val reqBody = (body ?: "").toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder().url(scannerBase() + path).post(reqBody)
        s.authHeader?.let { builder.header("Authorization", it) }

        val resp = client.newCall(builder.build()).await()
        resp.use {
            val text = it.body?.string().orEmpty()
            // Success and the backend's own error bodies are both JSON; map other
            // statuses to a control response carrying the HTTP error as the message.
            if (it.isSuccessful || it.code == 400) {
                return decode(text, ScannerControlResponse.serializer())
            }
            return ScannerControlResponse(error = "HTTP ${it.code} ${it.message}")
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
    }
}
