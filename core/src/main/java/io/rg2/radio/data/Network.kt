package io.rg2.radio.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Suspend bridge over OkHttp's async [Call], cancelling the in-flight call when
 * the coroutine is cancelled. Shared by [RadioApi] (the FM/AM backend) and
 * [ScannerApi] (the EMS/ATC scanner backend) — one well-tested bridge for both.
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWithException(RadioApiException("request failed: ${e.message}", e))
        }
    })
    cont.invokeOnCancellation {
        try {
            cancel()
        } catch (_: Throwable) {
        }
    }
}
