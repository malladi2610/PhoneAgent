package com.malladi2610.phoneagent.core.ollama

import com.malladi2610.phoneagent.core.agent.AgentEngine
import com.malladi2610.phoneagent.core.agent.AgentTurnResult
import com.malladi2610.phoneagent.core.agent.ChatMessage
import com.malladi2610.phoneagent.core.agent.ChatMessage.Role
import com.malladi2610.phoneagent.core.agent.StopReason
import com.malladi2610.phoneagent.core.agent.ToolCall
import com.malladi2610.phoneagent.core.agent.TurnEvent
import com.malladi2610.phoneagent.core.agent.TurnRequest
import com.malladi2610.phoneagent.core.tool.ToolContext
import com.malladi2610.phoneagent.core.tool.ToolOutcome
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Ollama tool loop (spec M1 §5.2): append the user message, call `/api/chat`, execute each
 * returned tool call in order through [TurnRequest.tools], append the results as `tool` messages,
 * and repeat until the model answers with text.
 *
 * - At most [maxSteps] tool steps (model replies with tool calls) run per turn. If the model still
 *   asks for tools after that, the turn ends with [StopReason.STEP_LIMIT] and those calls don't
 *   run.
 * - A tool that fails, is unknown or throws produces an error result for the model; it never
 *   aborts the turn.
 * - Service failures are thrown as [com.malladi2610.phoneagent.core.agent.AgentError]; cancelling
 *   the coroutine stops the turn (and the in-flight HTTP call).
 * - The conversation is left well formed in every case: every assistant tool call has a matching
 *   `tool` message, so the next turn's request is valid.
 */
class OllamaAgentEngine(
  private val client: OllamaChatApi,
  private val maxSteps: Int = DEFAULT_MAX_STEPS,
) : AgentEngine {

  override suspend fun runTurn(request: TurnRequest): AgentTurnResult {
    val conv = request.conversation
    val turnId = UUID.randomUUID().toString()
    val specs = request.tools.specs()
    conv.messages += ChatMessage(Role.USER, request.userText)

    var steps = 0
    var toolCallCount = 0
    try {
      while (true) {
        val result = client.chat(request.model, conv.messages.toList(), specs)
        conv.lastTokenCount = result.promptEvalCount + result.evalCount
        val reply = result.message.copy(role = Role.ASSISTANT)
        reply.thinking?.takeIf { it.isNotBlank() }?.let { request.onEvent(TurnEvent.Thinking(it)) }

        if (reply.toolCalls.isEmpty()) {
          conv.messages += reply
          return AgentTurnResult(reply.content, toolCallCount, StopReason.COMPLETED)
        }

        if (steps >= maxSteps) {
          // Don't run these calls, and don't keep them in history unanswered.
          val text = stepLimitText(reply.content)
          conv.messages += reply.copy(content = text, toolCalls = emptyList())
          return AgentTurnResult(text, toolCallCount, StopReason.STEP_LIMIT)
        }

        steps++
        conv.messages += reply
        for (call in reply.toolCalls) {
          currentCoroutineContext().ensureActive()
          val outcome = runTool(request, call, turnId, steps)
          toolCallCount++
          conv.messages +=
            ChatMessage(
              role = Role.TOOL,
              content = outcome.toJson().toString(),
              toolName = call.name,
              toolCallId = call.id,
            )
        }
      }
    } catch (e: Throwable) {
      answerDanglingToolCalls(conv.messages)
      throw e
    }
  }

  private suspend fun runTool(
    request: TurnRequest,
    call: ToolCall,
    turnId: String,
    step: Int,
  ): ToolOutcome {
    request.onEvent(TurnEvent.ToolStarted(call.name, call.arguments))
    val ctx =
      ToolContext(
        surface = request.surface,
        turnId = turnId,
        userRequest = request.userText,
        model = request.model,
        step = step,
        ui = request.ui,
      )
    val outcome =
      try {
        request.tools.execute(call.name, call.arguments, ctx)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        ToolOutcome.Error(e.message ?: e::class.java.simpleName)
      }
    val status = outcome.toJson()["status"]?.jsonPrimitive?.content ?: "unknown"
    request.onEvent(TurnEvent.ToolFinished(call.name, status))
    return outcome
  }

  private fun stepLimitText(partial: String): String {
    val note = "I stopped after $maxSteps tool steps (step limit reached)."
    return if (partial.isBlank()) note else "${partial.trim()}\n\n$note"
  }

  /** After an interrupted turn, gives every unanswered tool call of the last reply a result. */
  private fun answerDanglingToolCalls(messages: MutableList<ChatMessage>) {
    val lastAssistant = messages.indexOfLast { it.role == Role.ASSISTANT }
    if (lastAssistant < 0) return
    val calls = messages[lastAssistant].toolCalls
    if (calls.isEmpty()) return
    val answered =
      messages.subList(lastAssistant + 1, messages.size).filter { it.role == Role.TOOL }.size
    for (call in calls.drop(answered)) {
      messages +=
        ChatMessage(
          role = Role.TOOL,
          content = ToolOutcome.Error("not run: the turn was stopped").toJson().toString(),
          toolName = call.name,
          toolCallId = call.id,
        )
    }
  }

  companion object {
    const val DEFAULT_MAX_STEPS = 8
  }
}
