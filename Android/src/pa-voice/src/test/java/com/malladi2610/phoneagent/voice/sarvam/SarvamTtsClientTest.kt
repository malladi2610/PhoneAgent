package com.malladi2610.phoneagent.voice.sarvam

import com.malladi2610.phoneagent.core.agent.AgentError
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SarvamTtsClientTest {
  private lateinit var server: MockWebServer
  private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
  private val key = "sk_test_SECRET123"

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun client(keyValue: String? = key) =
    SarvamTtsClient(http, keyProvider = { keyValue }, baseUrl = server.url("/").toString())

  private suspend fun expectError(block: suspend () -> Unit): AgentError {
    try {
      block()
    } catch (e: AgentError) {
      return e
    }
    fail("expected AgentError")
    error("unreachable")
  }

  @Test
  fun sendsSubscriptionKeyHeader_andBulbulV3() = runTest {
    val wav = byteArrayOf(82, 73, 70, 70, 9, 9)
    server.enqueue(
      MockResponse()
        .setBody("""{"request_id":"r","audios":["${Base64.getEncoder().encodeToString(wav)}"]}""")
    )

    val audio = client().synthesize("It's 80 percent.", language = "en-IN", speaker = "ishita")

    assertArrayEquals(wav, audio)
    val req = server.takeRequest()
    assertEquals("POST", req.method)
    assertEquals("/text-to-speech", req.path)
    assertEquals(key, req.getHeader("api-subscription-key"))
    assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
    val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
    assertEquals("bulbul:v3", body["model"]!!.jsonPrimitive.content)
    assertEquals("ishita", body["speaker"]!!.jsonPrimitive.content)
    assertEquals("en-IN", body["language_code"]!!.jsonPrimitive.content)
    assertEquals("It's 80 percent.", body["text"]!!.jsonPrimitive.content)
  }

  @Test
  fun forbidden_mapsToUnauthorized() = runTest {
    server.enqueue(
      MockResponse()
        .setResponseCode(403)
        .setBody(
          """{"error":{"message":"Invalid or missing authentication credentials","code":"invalid_api_key_error","request_id":"x"}}"""
        )
    )

    val e = expectError { client().synthesize("hi", "en-IN", "ishita") }

    assertTrue(e is AgentError.Unauthorized)
    assertTrue(e.userMessage.contains("Sarvam"))
    assertFalse(e.message!!.contains(key))
  }

  @Test
  fun quota402_mapsToQuotaExceeded() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(402).setBody("""{"error":{"message":"no credits","code":"insufficient_quota_error"}}""")
    )

    val e = expectError { client().synthesize("hi", "en-IN", "ishita") }

    assertTrue(e is AgentError.QuotaExceeded)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun rateLimited429_mapsToRateLimited_noRetry() = runTest {
    server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))

    val e = expectError { client().synthesize("hi", "en-IN", "ishita") }

    assertTrue(e is AgentError.RateLimited)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun serverError_mapsToServer() = runTest {
    server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))

    val e = expectError { client().synthesize("hi", "en-IN", "ishita") }

    assertTrue(e is AgentError.Server)
  }

  @Test
  fun missingKey_throwsMissingKey_withoutRequest() = runTest {
    val e = expectError { client(keyValue = null).synthesize("hi", "en-IN", "ishita") }

    assertTrue(e is AgentError.MissingKey)
    assertEquals(0, server.requestCount)
  }

  @Test
  fun ioFailure_mapsToNetwork() = runTest {
    server.shutdown()

    val e = expectError { client().synthesize("hi", "en-IN", "ishita") }

    assertTrue(e is AgentError.Network)
  }

  @Test
  fun longText_isTruncatedTo2500Chars() = runTest {
    server.enqueue(MockResponse().setBody("""{"request_id":"r","audios":["AAAA"]}"""))

    client().synthesize("a".repeat(3000), "en-IN", "ishita")

    val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
    assertEquals(2500, body["text"]!!.jsonPrimitive.content.length)
  }
}
