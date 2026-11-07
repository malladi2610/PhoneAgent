package com.malladi2610.phoneagent.core.ollama

import com.malladi2610.phoneagent.core.agent.AgentError
import com.malladi2610.phoneagent.core.agent.ChatMessage
import com.malladi2610.phoneagent.core.agent.ChatMessage.Role
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OllamaClientTest {
  private val key = "sk-test-0123456789"
  private lateinit var server: MockWebServer
  private var currentKey: String? = key

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    runCatching { server.shutdown() }
  }

  private fun client(baseUrl: String = server.url("/").toString().trimEnd('/')) =
    OllamaClient(
      http = OkHttpClient(),
      keyProvider = { currentKey },
      baseUrl = baseUrl,
      retryDelayMs = 10,
    )

  private val messages = listOf(ChatMessage(Role.USER, "hi"))

  private suspend inline fun <reified T : AgentError> assertThrowsAgentError(
    crossinline block: suspend () -> Unit
  ): T {
    try {
      block()
    } catch (e: AgentError) {
      if (e is T) return e
      fail("Expected ${T::class.simpleName} but got ${e::class.simpleName}: ${e.message}")
    }
    fail("Expected ${T::class.simpleName} but nothing was thrown")
    error("unreachable")
  }

  @Test
  fun sendsBearerKeyAndStreamFalse() = runTest {
    server.enqueue(MockResponse().setBody(Fixtures.read("ollama-chat-resp2.json")))
    val result = client().chat("deepseek-v4.1-flash", messages, emptyList())

    assertEquals("Done — the torch is on and media volume is set to 30%.", result.message.content)
    assertEquals(540, result.promptEvalCount)
    assertEquals(17, result.evalCount)

    val req = server.takeRequest()
    assertEquals("POST", req.method)
    assertEquals("/api/chat", req.path)
    assertEquals("Bearer $key", req.getHeader("Authorization"))
    assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
    val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
    assertEquals("false", body["stream"]!!.jsonPrimitive.content)
    assertEquals("deepseek-v4.1-flash", body["model"]!!.jsonPrimitive.content)
  }

  @Test
  fun unauthorized_mapsToAgentErrorUnauthorized() = runTest {
    for (code in listOf(401, 403)) {
      server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":"Unauthorized"}"""))
      val e = assertThrowsAgentError<AgentError.Unauthorized> { client().chat("m", messages, emptyList()) }
      assertTrue(e.userMessage.contains("Ollama"))
      assertFalse(e.userMessage.contains(key))
    }
    assertEquals(2, server.requestCount)
  }

  @Test
  fun rateLimited_noRetry() = runTest {
    server.enqueue(MockResponse().setResponseCode(429))
    server.enqueue(MockResponse().setBody(Fixtures.read("ollama-chat-resp2.json")))
    assertThrowsAgentError<AgentError.RateLimited> { client().chat("m", messages, emptyList()) }
    assertEquals(1, server.requestCount)
  }

  @Test
  fun serverError_retriesOnceThenThrowsServer() = runTest {
    server.enqueue(MockResponse().setResponseCode(500))
    server.enqueue(MockResponse().setResponseCode(503))
    server.enqueue(MockResponse().setBody(Fixtures.read("ollama-chat-resp2.json")))
    val e = assertThrowsAgentError<AgentError.Server> { client().chat("m", messages, emptyList()) }
    assertTrue(e.userMessage.contains("503"))
    assertEquals(2, server.requestCount)
  }

  @Test
  fun serverError_thenSuccessOnRetry() = runTest {
    server.enqueue(MockResponse().setResponseCode(502))
    server.enqueue(MockResponse().setBody(Fixtures.read("ollama-chat-resp2.json")))
    val result = client().chat("m", messages, emptyList())
    assertEquals(17, result.evalCount)
    assertEquals(2, server.requestCount)
  }

  @Test
  fun missingKey_throwsMissingKey_withoutRequest() = runTest {
    for (k in listOf(null, "", "   ")) {
      currentKey = k
      assertThrowsAgentError<AgentError.MissingKey> { client().chat("m", messages, emptyList()) }
      assertThrowsAgentError<AgentError.MissingKey> { client().listModels() }
    }
    assertEquals(0, server.requestCount)
  }

  @Test
  fun ioFailure_mapsToNetwork() = runTest {
    val url = server.url("/").toString().trimEnd('/')
    server.shutdown()
    assertThrowsAgentError<AgentError.Network> { client(url).chat("m", messages, emptyList()) }
  }

  @Test
  fun malformedBody_mapsToServerError() = runTest {
    server.enqueue(MockResponse().setBody("<html>oops</html>"))
    assertThrowsAgentError<AgentError.Server> { client().chat("m", messages, emptyList()) }
  }

  @Test
  fun listModels_parsesTags() = runTest {
    server.enqueue(
      MockResponse()
        .setBody(
          """{"models":[
            {"name":"kimi-k3","model":"kimi-k3","capabilities":["completion","tools"]},
            {"name":"embed-only","model":"embed-only","capabilities":["embedding"]},
            {"name":"deepseek-v4.1-flash","model":"deepseek-v4.1-flash","size":1}
          ]}"""
        )
    )
    // Models that advertise capabilities without "tools" can't drive the phone, so they're skipped.
    assertEquals(listOf("kimi-k3", "deepseek-v4.1-flash"), client().listModels())
    val req = server.takeRequest()
    assertEquals("GET", req.method)
    assertEquals("/api/tags", req.path)
    assertEquals("Bearer $key", req.getHeader("Authorization"))
  }

  @Test
  fun listModels_unauthorized() = runTest {
    server.enqueue(MockResponse().setResponseCode(401))
    assertThrowsAgentError<AgentError.Unauthorized> { client().listModels() }
  }

  @Test
  fun defaultBaseUrl_isOllamaCom() {
    assertEquals("https://ollama.com", OllamaClient.DEFAULT_BASE_URL)
  }

  @Test
  fun keyWithPastedLineBreak_isSentWithoutWhitespace() = runTest {
    currentKey = "sk-test\n0123 456789"
    server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[]}"""))
    client().listModels()
    assertEquals("Bearer sk-test0123456789", server.takeRequest().getHeader("Authorization"))
  }

  @Test
  fun verifyKey_postsToApiMe_withBearer() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).setBody("""{"name":"someone"}"""))
    client().verifyKey()
    val req = server.takeRequest()
    assertEquals("POST", req.method)
    assertEquals("/api/me", req.path)
    assertEquals("Bearer $key", req.getHeader("Authorization"))
  }

  @Test
  fun verifyKey_401_throwsUnauthorized() = runTest {
    server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid credentials"}"""))
    assertThrowsAgentError<AgentError.Unauthorized> { client().verifyKey() }
  }
}
