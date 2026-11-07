package com.malladi2610.phoneagent.voice.sarvam

import com.malladi2610.phoneagent.core.agent.AgentError
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Sarvam REST `/text-to-speech` (bulbul:v3, 24 kHz WAV). Throws [AgentError]; never logs the key. */
class SarvamTtsClient(
  private val http: OkHttpClient,
  private val keyProvider: () -> String?,
  baseUrl: String = SarvamWire.REST_BASE,
) {
  private val endpoint = baseUrl.trimEnd('/') + "/text-to-speech"

  /** Returns a complete WAV file. */
  suspend fun synthesize(text: String, language: String, speaker: String): ByteArray {
    val key = keyProvider()?.takeIf { it.isNotBlank() } ?: throw AgentError.MissingKey(SarvamWire.SERVICE)
    val body = SarvamWire.ttsRequestJson(text.take(MAX_CHARS), language, speaker)
    val request =
      Request.Builder()
        .url(endpoint)
        .header(SarvamWire.REST_KEY_HEADER, key)
        .post(body.toRequestBody(JSON))
        .build()
    val response =
      try {
        http.newCall(request).await()
      } catch (e: IOException) {
        throw AgentError.Network(SarvamWire.SERVICE)
      }
    response.use {
      val text = it.body?.string().orEmpty()
      if (!it.isSuccessful) throw SarvamWire.errorFor(it.code, errorCode(text))
      return try {
        SarvamWire.decodeTtsAudio(text)
      } catch (e: AgentError) {
        throw e
      } catch (e: Exception) {
        throw AgentError.Server(SarvamWire.SERVICE, it.code)
      }
    }
  }

  private fun errorCode(body: String): String? =
    runCatching {
        Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
      }
      .getOrNull()

  companion object {
    const val MAX_CHARS = 2500
    private val JSON = "application/json".toMediaType()
  }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
  cont.invokeOnCancellation { runCatching { cancel() } }
  enqueue(
    object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        if (cont.isActive) cont.resumeWithException(e)
      }

      override fun onResponse(call: Call, response: Response) {
        if (cont.isActive) cont.resume(response) else response.close()
      }
    }
  )
}
