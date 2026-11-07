package com.malladi2610.phoneagent.core.ollama

import com.malladi2610.phoneagent.core.agent.ChatMessage
import com.malladi2610.phoneagent.core.agent.ToolCall
import com.malladi2610.phoneagent.core.tool.ToolSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Ollama `/api/chat` and `/api/tags` wire DTOs, mirroring the fixtures captured against Ollama
 * 0.34.2 (`docs/superpowers/specs/fixtures/ollama-chat-*.json`, spec M1 §6).
 */
@Serializable
data class WireChatRequest(
  val model: String,
  val stream: Boolean = false,
  val messages: List<WireMessage>,
  val tools: List<WireTool>? = null,
)

@Serializable
data class WireMessage(
  val role: String,
  val content: String = "",
  @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
  @SerialName("tool_name") val toolName: String? = null,
  @SerialName("tool_call_id") val toolCallId: String? = null,
  val thinking: String? = null,
)

@Serializable data class WireToolCall(val id: String? = null, val function: WireFunctionCall)

@Serializable
data class WireFunctionCall(
  val index: Int? = null,
  val name: String,
  /** Normally a JSON object; some models send a JSON-encoded string instead. */
  val arguments: JsonElement? = null,
)

@Serializable data class WireTool(val type: String = "function", val function: WireFunction)

@Serializable
data class WireFunction(val name: String, val description: String, val parameters: JsonObject)

@Serializable
data class WireChatResponse(
  val model: String? = null,
  val message: WireMessage,
  val done: Boolean = false,
  @SerialName("prompt_eval_count") val promptEvalCount: Int = 0,
  @SerialName("eval_count") val evalCount: Int = 0,
)

@Serializable data class WireTagsResponse(val models: List<WireModelTag> = emptyList())

@Serializable
data class WireModelTag(
  val name: String,
  val model: String? = null,
  /** e.g. ["completion","tools","thinking"]; absent on older servers. */
  val capabilities: List<String>? = null,
)

/** Result of one `/api/chat` call. */
data class OllamaChatResult(
  val message: ChatMessage,
  val promptEvalCount: Int,
  val evalCount: Int,
)

object OllamaWire {
  val json: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
  }

  private val EMPTY = JsonObject(emptyMap())

  fun encodeChatRequest(model: String, messages: List<ChatMessage>, tools: List<ToolSpec>): String =
    json.encodeToString(
      WireChatRequest.serializer(),
      WireChatRequest(
        model = model,
        stream = false,
        messages = messages.map(::toWire),
        tools = tools.takeIf { it.isNotEmpty() }?.map(::toWire),
      ),
    )

  fun decodeChatResult(body: String): OllamaChatResult {
    val resp = json.decodeFromString(WireChatResponse.serializer(), body)
    return OllamaChatResult(
      message = fromWire(resp.message),
      promptEvalCount = resp.promptEvalCount,
      evalCount = resp.evalCount,
    )
  }

  fun decodeTags(body: String): WireTagsResponse =
    json.decodeFromString(WireTagsResponse.serializer(), body)

  fun toWire(spec: ToolSpec): WireTool =
    WireTool(function = WireFunction(spec.name, spec.description, spec.parameters))

  fun toWire(msg: ChatMessage): WireMessage =
    WireMessage(
      role = msg.role.wire,
      content = msg.content,
      toolCalls =
        msg.toolCalls
          .takeIf { it.isNotEmpty() }
          ?.mapIndexed { i, call ->
            WireToolCall(
              id = call.id,
              function = WireFunctionCall(index = i, name = call.name, arguments = call.arguments),
            )
          },
      toolName = msg.toolName,
      toolCallId = msg.toolCallId,
      thinking = msg.thinking?.takeIf { it.isNotEmpty() },
    )

  fun fromWire(msg: WireMessage): ChatMessage =
    ChatMessage(
      role = ChatMessage.Role.entries.firstOrNull { it.wire == msg.role } ?: ChatMessage.Role.ASSISTANT,
      content = msg.content,
      toolCalls =
        msg.toolCalls.orEmpty().map { call ->
          ToolCall(
            id = call.id,
            name = call.function.name,
            arguments = argumentsObject(call.function.arguments),
          )
        },
      toolName = msg.toolName,
      toolCallId = msg.toolCallId,
      thinking = msg.thinking?.takeIf { it.isNotEmpty() },
    )

  /**
   * Arguments arrive as an object (verified), but tolerate a JSON-encoded string. Anything else
   * becomes `{}`, so the tool's own validation reports the missing arguments back to the model.
   */
  private fun argumentsObject(el: JsonElement?): JsonObject =
    when {
      el is JsonObject -> el
      el is JsonPrimitive && el.isString ->
        runCatching { json.parseToJsonElement(el.content) as? JsonObject }.getOrNull() ?: EMPTY
      else -> EMPTY
    }
}
