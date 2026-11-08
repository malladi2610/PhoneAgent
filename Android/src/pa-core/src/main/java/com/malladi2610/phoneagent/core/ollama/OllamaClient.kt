package com.malladi2610.phoneagent.core.ollama

import com.malladi2610.phoneagent.core.agent.AgentError
import com.malladi2610.phoneagent.core.agent.ChatMessage
import com.malladi2610.phoneagent.core.secrets.ApiKeys
import com.malladi2610.phoneagent.core.tool.ToolSpec
import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** The part of [OllamaClient] the agent engine needs; lets tests substitute a fake. */
interface OllamaChatApi {
  suspend fun chat(model: String, messages: List<ChatMessage>, tools: List<ToolSpec>): OllamaChatResult
}

/**
 * Minimal Ollama Cloud client (spec M1 §5.1, plan "Global Constraints"):
 * - `POST {baseUrl}/api/chat` with `stream:false`; `GET {baseUrl}/api/tags`.
 * - `Authorization: Bearer <key>`; the key comes from [keyProvider] on every call and is never
 *   logged or put into an exception message.
 * - 60 s per request; one retry after [retryDelayMs] on 5xx; no retry on 429.
 * - Failures are thrown as [AgentError] (401/403 → Unauthorized, 402 → QuotaExceeded,
 *   429 → RateLimited, I/O → Network, anything else → Server).
 */
class OllamaClient(
  http: OkHttpClient,
  private val keyProvider: () -> String?,
  private val baseUrl: String = DEFAULT_BASE_URL,
  private val retryDelayMs: Long = 1_000,
) : OllamaChatApi {
  private val http: OkHttpClient =
    http
      .newBuilder()
      .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .build()

  override suspend fun chat(
    model: String,
    messages: List<ChatMessage>,
    tools: List<ToolSpec>,
  ): OllamaChatResult {
    val key = requireKey()
    val body = OllamaWire.encodeChatRequest(model, messages, tools).toRequestBody(JSON)
    val request =
      Request.Builder()
        .url("${baseUrl.trimEnd('/')}/api/chat")
        .header("Authorization", "Bearer $key")
        .post(body)
        .build()
    val text = send(request)
    return decode { OllamaWire.decodeChatResult(text) }
  }

  /**
   * Model names exactly as returned by `/api/tags`. Models that list capabilities without "tools"
   * are skipped, since the agent always sends tools.
   */
  suspend fun listModels(): List<String> {
    val key = requireKey()
    val request =
      Request.Builder()
        .url("${baseUrl.trimEnd('/')}/api/tags")
        .header("Authorization", "Bearer $key")
        .get()
        .build()
    val text = send(request)
    val tags = decode { OllamaWire.decodeTags(text) }
    return tags.models
      .filter { it.capabilities == null || "tools" in it.capabilities }
      .map { it.name }
  }

  /**
   * Checks the key against an endpoint that actually authenticates (`POST /api/me`).
   * `/api/tags` is public and succeeds with any key, so it can't validate one.
   */
  suspend fun verifyKey() {
    val key = requireKey()
    val request =
      Request.Builder()
        .url("${baseUrl.trimEnd('/')}/api/me")
        .header("Authorization", "Bearer $key")
        .post(ByteArray(0).toRequestBody(null))
        .build()
    send(request)
  }

  private fun requireKey(): String {
    val key =
      keyProvider()?.let(ApiKeys::normalize)?.takeIf { it.isNotEmpty() }
        ?: throw AgentError.MissingKey(SERVICE)
    // Diagnostics: only the key's shape, never its value.
    Log.d(
      TAG,
      "key shape: length=${key.length} hasWhitespace=${key.any { it.isWhitespace() }} " +
        "looksLikeSshKey=${key.startsWith("ssh-")} dots=${key.count { it == '.' }}",
    )
    return key
  }

  private inline fun <T> decode(block: () -> T): T =
    try {
      block()
    } catch (e: SerializationException) {
      throw AgentError.Server(SERVICE, 200)
    } catch (e: IllegalArgumentException) {
      throw AgentError.Server(SERVICE, 200)
    }

  /** Sends [request], retrying once on 5xx, and returns the body of a 2xx response. */
  private suspend fun send(request: Request): String {
    var attempt = 0
    while (true) {
      attempt++
      val (code, body) =
        try {
          http.newCall(request).await()
        } catch (e: IOException) {
          throw AgentError.Network(SERVICE)
        }
      if (code in 200..299) return body
      Log.w(
        TAG,
        "HTTP $code ${request.method} ${request.url.encodedPath} body=${body.take(300).replace('\n', ' ')}",
      )
      when {
        code == 401 || code == 403 -> throw AgentError.Unauthorized(SERVICE)
        code == 402 -> throw AgentError.QuotaExceeded(SERVICE)
        code == 429 -> throw AgentError.RateLimited(SERVICE)
        code in 500..599 && attempt == 1 -> delay(retryDelayMs)
        else -> throw AgentError.Server(SERVICE, code)
      }
    }
  }

  /** Executes the call asynchronously; cancelling the coroutine cancels the HTTP call. */
  private suspend fun Call.await(): Pair<Int, String> =
    suspendCancellableCoroutine { cont ->
      cont.invokeOnCancellation { runCatching { cancel() } }
      enqueue(
        object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
          }

          override fun onResponse(call: Call, response: Response) {
            val result =
              try {
                response.use { it.code to (it.body?.string() ?: "") }
              } catch (e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
                return
              }
            if (cont.isActive) cont.resume(result)
          }
        }
      )
    }

  companion object {
    const val DEFAULT_BASE_URL = "https://ollama.com"
    const val SERVICE = "Ollama"
    private const val TAG = "PaOllama"
    private const val TIMEOUT_SECONDS = 60L
    private val JSON = "application/json; charset=utf-8".toMediaType()
  }
}
