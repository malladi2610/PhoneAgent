package com.malladi2610.phoneagent.core.ollama

import com.malladi2610.phoneagent.core.agent.ChatMessage
import com.malladi2610.phoneagent.core.agent.ChatMessage.Role
import com.malladi2610.phoneagent.core.agent.ToolCall
import com.malladi2610.phoneagent.core.tool.ToolRisk
import com.malladi2610.phoneagent.core.tool.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaWireTest {
  private val system =
    "You control an Android phone using tools. Use tools when the user asks for a phone action."
  private val user = "Turn on the torch and set media volume to 30%"

  /** The two tools from the fixtures, as [ToolSpec]s. */
  private fun fixtureTools(): List<ToolSpec> =
    Json.parseToJsonElement(Fixtures.read("ollama-chat-req1.json"))
      .jsonObject["tools"]!!
      .jsonArray
      .map {
        val fn = it.jsonObject["function"]!!.jsonObject
        ToolSpec(
          name = fn["name"]!!.jsonPrimitive.content,
          description = fn["description"]!!.jsonPrimitive.content,
          parameters = fn["parameters"]!!.jsonObject,
          risk = ToolRisk.SAFE,
        )
      }

  @Test
  fun decodesToolCallResponse() {
    val result = OllamaWire.decodeChatResult(Fixtures.read("ollama-chat-resp1.json"))
    val msg = result.message
    assertEquals(Role.ASSISTANT, msg.role)
    assertEquals("I'll do both right away.", msg.content)
    assertEquals(2, msg.toolCalls.size)
    assertEquals(
      ToolCall("call_rpmyhjsh", "set_flashlight", JsonObject(mapOf("on" to JsonPrimitive(true)))),
      msg.toolCalls[0],
    )
    assertEquals(
      ToolCall(
        "call_w2puekrw",
        "set_volume",
        JsonObject(mapOf("stream" to JsonPrimitive("media"), "percent" to JsonPrimitive(30))),
      ),
      msg.toolCalls[1],
    )
    assertEquals(417, result.promptEvalCount)
    assertEquals(92, result.evalCount)
  }

  @Test
  fun decodesFinalResponse() {
    val result = OllamaWire.decodeChatResult(Fixtures.read("ollama-chat-resp2.json"))
    assertEquals("Done — the torch is on and media volume is set to 30%.", result.message.content)
    assertTrue(result.message.toolCalls.isEmpty())
    assertEquals(540, result.promptEvalCount)
    assertEquals(17, result.evalCount)
  }

  @Test
  fun encodesFirstRequestLikeFixture() {
    val body =
      OllamaWire.encodeChatRequest(
        model = "deepseek-v4.1-flash:cloud",
        messages = listOf(ChatMessage(Role.SYSTEM, system), ChatMessage(Role.USER, user)),
        tools = fixtureTools(),
      )
    assertEquals(
      Json.parseToJsonElement(Fixtures.read("ollama-chat-req1.json")),
      Json.parseToJsonElement(body),
    )
  }

  @Test
  fun encodesFollowUpRequestWithToolMessagesLikeFixture() {
    val assistant =
      OllamaWire.decodeChatResult(Fixtures.read("ollama-chat-resp1.json")).message
    val body =
      OllamaWire.encodeChatRequest(
        model = "deepseek-v4.1-flash:cloud",
        messages =
          listOf(
            ChatMessage(Role.SYSTEM, system),
            ChatMessage(Role.USER, user),
            assistant,
            ChatMessage(
              Role.TOOL,
              content = "{\"status\": \"ok\"}",
              toolName = "set_flashlight",
              toolCallId = "call_rpmyhjsh",
            ),
            ChatMessage(
              Role.TOOL,
              content = "{\"status\": \"ok\"}",
              toolName = "set_volume",
              toolCallId = "call_w2puekrw",
            ),
          ),
        tools = fixtureTools(),
      )
    assertEquals(
      Json.parseToJsonElement(Fixtures.read("ollama-chat-req2.json")),
      Json.parseToJsonElement(body),
    )
  }

  @Test
  fun stringArguments_areParsedAsObject() {
    val body =
      """{"message":{"role":"assistant","content":"","tool_calls":[{"function":""" +
        """{"name":"set_flashlight","arguments":"{\"on\":true}"}}]},"done":true}"""
    val call = OllamaWire.decodeChatResult(body).message.toolCalls.single()
    assertEquals(null, call.id)
    assertEquals(JsonObject(mapOf("on" to JsonPrimitive(true))), call.arguments)
  }

  @Test
  fun malformedArguments_becomeEmptyObject() {
    val body =
      """{"message":{"role":"assistant","content":"","tool_calls":[{"function":""" +
        """{"name":"set_flashlight","arguments":"not json"}}]},"done":true}"""
    val call = OllamaWire.decodeChatResult(body).message.toolCalls.single()
    assertEquals("set_flashlight", call.name)
    assertEquals(JsonObject(emptyMap()), call.arguments)
  }

  @Test
  fun decodesThinking() {
    val body =
      """{"message":{"role":"assistant","content":"hi","thinking":"hmm"},"done":true,"extra":1}"""
    assertEquals("hmm", OllamaWire.decodeChatResult(body).message.thinking)
  }
}
