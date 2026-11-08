package com.malladi2610.phoneagent.core.agent

import com.malladi2610.phoneagent.core.tool.Surface
import com.malladi2610.phoneagent.core.tool.ToolRegistry
import com.malladi2610.phoneagent.core.tool.ToolUi
import kotlinx.serialization.json.JsonObject

/** A message in Ollama's /api/chat format. */
data class ChatMessage(
  val role: Role,
  val content: String = "",
  val toolCalls: List<ToolCall> = emptyList(),
  /** For [Role.TOOL] messages: which tool produced this result. */
  val toolName: String? = null,
  val toolCallId: String? = null,
  val thinking: String? = null,
) {
  enum class Role(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
  }
}

data class ToolCall(val id: String?, val name: String, val arguments: JsonObject)

/** Mutable chat history for one conversation. Not thread-safe: one turn at a time. */
class Conversation(systemPrompt: String? = null) {
  val messages: MutableList<ChatMessage> =
    mutableListOf<ChatMessage>().apply {
      if (systemPrompt != null) add(ChatMessage(ChatMessage.Role.SYSTEM, systemPrompt))
    }
  var lastTokenCount: Int = 0
}

/** Progress events emitted during a turn, for UI ("Turning on torch…"). */
sealed interface TurnEvent {
  data class ToolStarted(val name: String, val args: JsonObject) : TurnEvent

  data class ToolFinished(val name: String, val status: String) : TurnEvent

  data class Thinking(val text: String) : TurnEvent
}

data class TurnRequest(
  val conversation: Conversation,
  val userText: String,
  /** Ollama model name exactly as returned by /api/tags. */
  val model: String,
  val surface: Surface,
  val tools: ToolRegistry,
  val ui: ToolUi,
  val onEvent: (TurnEvent) -> Unit = {},
)

enum class StopReason {
  COMPLETED,
  STEP_LIMIT,
}

data class AgentTurnResult(val text: String, val toolCallCount: Int, val stopReason: StopReason)

/** Errors a turn can fail with; [userMessage] is shown/spoken to the user (spec M1 §7). */
sealed class AgentError(val userMessage: String) : Exception(userMessage) {
  class MissingKey(service: String) : AgentError("Add your $service API key in PhoneAgent settings.")

  class Unauthorized(service: String) :
    AgentError("Your $service API key was rejected. Add a valid key in PhoneAgent settings.")

  class RateLimited(service: String) : AgentError("$service rate limit hit — try again in a moment.")

  class QuotaExceeded(service: String) : AgentError("Your $service credits have run out.")

  class Network(service: String) : AgentError("Can't reach $service. Check your connection.")

  class Server(service: String, code: Int) : AgentError("$service had a problem (HTTP $code).")
}

/**
 * Runs one user turn: model call → tool calls → … → final text (max 8 tool steps, spec M1 §5.2).
 * Throws [AgentError] for service failures. Cancellation (coroutine cancel) stops the turn.
 */
interface AgentEngine {
  suspend fun runTurn(request: TurnRequest): AgentTurnResult
}
