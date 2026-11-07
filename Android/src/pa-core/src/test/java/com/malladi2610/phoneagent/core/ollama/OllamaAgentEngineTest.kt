package com.malladi2610.phoneagent.core.ollama

import com.malladi2610.phoneagent.core.agent.AgentError
import com.malladi2610.phoneagent.core.agent.ChatMessage
import com.malladi2610.phoneagent.core.agent.ChatMessage.Role
import com.malladi2610.phoneagent.core.agent.Conversation
import com.malladi2610.phoneagent.core.agent.StopReason
import com.malladi2610.phoneagent.core.agent.ToolCall
import com.malladi2610.phoneagent.core.agent.TurnEvent
import com.malladi2610.phoneagent.core.agent.TurnRequest
import com.malladi2610.phoneagent.core.tool.Surface
import com.malladi2610.phoneagent.core.tool.ToolContext
import com.malladi2610.phoneagent.core.tool.ToolOutcome
import com.malladi2610.phoneagent.core.tool.ToolRegistry
import com.malladi2610.phoneagent.core.tool.ToolRisk
import com.malladi2610.phoneagent.core.tool.ToolSpec
import com.malladi2610.phoneagent.core.tool.ToolUi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OllamaAgentEngineTest {
  private val model = "deepseek-v4.1-flash"
  private val system =
    "You control an Android phone using tools. Use tools when the user asks for a phone action."
  private val userText = "Turn on the torch and set media volume to 30%"

  /** Scripted model: returns [replies] in order (the last one repeats); records every request. */
  private class FakeChat(private val replies: List<() -> OllamaChatResult>) : OllamaChatApi {
    val requests = mutableListOf<List<ChatMessage>>()
    val toolSets = mutableListOf<List<ToolSpec>>()

    override suspend fun chat(
      model: String,
      messages: List<ChatMessage>,
      tools: List<ToolSpec>,
    ): OllamaChatResult {
      requests += messages.toList()
      toolSets += tools
      return replies[minOf(requests.size - 1, replies.size - 1)]()
    }
  }

  private class FakeRegistry(
    private val specs: List<ToolSpec> = emptyList(),
    private val onExecute: suspend (String, JsonObject, ToolContext) -> ToolOutcome = { _, _, _ ->
      ToolOutcome.Ok()
    },
  ) : ToolRegistry {
    val calls = mutableListOf<Triple<String, JsonObject, ToolContext>>()

    override fun specs() = specs

    override suspend fun execute(name: String, args: JsonObject, ctx: ToolContext): ToolOutcome {
      calls += Triple(name, args, ctx)
      return onExecute(name, args, ctx)
    }
  }

  private object NoUi : ToolUi {
    override suspend fun confirm(summary: String) = true

    override suspend fun requestRuntimePermission(permission: String) = true

    override suspend fun requestUnlock() = true
  }

  private val flashSpec =
    ToolSpec("set_flashlight", "Turns the flashlight on or off", JsonObject(emptyMap()), ToolRisk.SAFE)

  private fun resp1() = OllamaWire.decodeChatResult(Fixtures.read("ollama-chat-resp1.json"))

  private fun resp2() = OllamaWire.decodeChatResult(Fixtures.read("ollama-chat-resp2.json"))

  private fun text(t: String) = OllamaChatResult(ChatMessage(Role.ASSISTANT, t), 10, 5)

  private fun toolCallReply(name: String, id: String = "call_x") =
    OllamaChatResult(
      ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(ToolCall(id, name, JsonObject(emptyMap())))),
      1,
      1,
    )

  private fun request(
    conversation: Conversation,
    tools: ToolRegistry,
    text: String = userText,
    onEvent: (TurnEvent) -> Unit = {},
  ) = TurnRequest(conversation, text, model, Surface.CHAT, tools, NoUi, onEvent)

  @Test
  fun toolCallsExecutedInOrder_thenFinalText() = runTest {
    val chat = FakeChat(listOf(::resp1, ::resp2))
    val registry = FakeRegistry(specs = listOf(flashSpec))
    val conv = Conversation(system)

    val result = OllamaAgentEngine(chat).runTurn(request(conv, registry))

    assertEquals("Done — the torch is on and media volume is set to 30%.", result.text)
    assertEquals(2, result.toolCallCount)
    assertEquals(StopReason.COMPLETED, result.stopReason)
    assertEquals(listOf("set_flashlight", "set_volume"), registry.calls.map { it.first })
    assertEquals(JsonObject(mapOf("on" to JsonPrimitive(true))), registry.calls[0].second)

    val ctx = registry.calls[0].third
    assertEquals(Surface.CHAT, ctx.surface)
    assertEquals(userText, ctx.userRequest)
    assertEquals(model, ctx.model)
    assertEquals(1, ctx.step)
    assertTrue(ctx.turnId.isNotEmpty())
    assertEquals(ctx.turnId, registry.calls[1].third.turnId)

    // Tools are sent on every request.
    assertEquals(listOf(listOf(flashSpec), listOf(flashSpec)), chat.toolSets)

    // The follow-up request has the req2 fixture's structure.
    val second = chat.requests[1]
    assertEquals(
      listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL, Role.TOOL),
      second.map { it.role },
    )
    assertEquals(2, second[2].toolCalls.size)
    assertEquals("set_flashlight", second[3].toolName)
    assertEquals("call_rpmyhjsh", second[3].toolCallId)
    assertEquals("""{"status":"ok"}""", second[3].content)
    assertEquals("set_volume", second[4].toolName)
    assertEquals("call_w2puekrw", second[4].toolCallId)

    assertEquals(540 + 17, conv.lastTokenCount)
    assertEquals(Role.ASSISTANT, conv.messages.last().role)
    assertEquals(result.text, conv.messages.last().content)
  }

  @Test
  fun unknownTool_returnsErrorResultToModel() = runTest {
    val chat = FakeChat(listOf({ toolCallReply("launch_rockets", "call_r") }, { text("Sorry") }))
    val error = ToolOutcome.Error("unknown tool: launch_rockets")
    val registry = FakeRegistry { _, _, _ -> error }

    val result = OllamaAgentEngine(chat).runTurn(request(Conversation(system), registry))

    assertEquals("Sorry", result.text)
    assertEquals(StopReason.COMPLETED, result.stopReason)
    val toolMsg = chat.requests[1].last()
    assertEquals(Role.TOOL, toolMsg.role)
    assertEquals("launch_rockets", toolMsg.toolName)
    assertEquals("call_r", toolMsg.toolCallId)
    assertEquals(error.toJson().toString(), toolMsg.content)
  }

  @Test
  fun registryThrows_returnsErrorResultToModel() = runTest {
    val chat = FakeChat(listOf({ toolCallReply("set_flashlight") }, { text("ok") }))
    val registry = FakeRegistry { _, _, _ -> throw IllegalStateException("torch busy") }

    val result = OllamaAgentEngine(chat).runTurn(request(Conversation(system), registry))

    assertEquals("ok", result.text)
    assertEquals(ToolOutcome.Error("torch busy").toJson().toString(), chat.requests[1].last().content)
  }

  @Test
  fun stepLimit_stopsAtEight() = runTest {
    val chat = FakeChat(listOf({ toolCallReply("set_flashlight") }))
    val registry = FakeRegistry()
    val conv = Conversation(system)

    val result = OllamaAgentEngine(chat).runTurn(request(conv, registry))

    assertEquals(8, registry.calls.size)
    assertEquals(8, result.toolCallCount)
    assertEquals(StopReason.STEP_LIMIT, result.stopReason)
    assertEquals((1..8).toList(), registry.calls.map { it.third.step })
    assertTrue(result.text.contains("step limit", ignoreCase = true))
    // History stays valid: no unanswered tool calls are left at the end.
    val last = conv.messages.last()
    assertEquals(Role.ASSISTANT, last.role)
    assertTrue(last.toolCalls.isEmpty())
  }

  @Test
  fun cancellation_stopsTurn() = runTest {
    val chat = FakeChat(listOf(::resp1, ::resp2))
    val started = CompletableDeferred<Unit>()
    val registry = FakeRegistry { _, _, _ ->
      started.complete(Unit)
      awaitCancellation()
    }
    val conv = Conversation(system)

    val job = launch { OllamaAgentEngine(chat).runTurn(request(conv, registry)) }
    started.await()
    job.cancel()
    job.join()

    assertTrue(job.isCancelled)
    assertEquals(1, chat.requests.size)
    assertEquals(listOf("set_flashlight"), registry.calls.map { it.first })
    // Unanswered tool calls get a "not run" result so the next turn's request is well formed.
    val toolMsgs = conv.messages.filter { it.role == Role.TOOL }
    assertEquals(listOf("call_rpmyhjsh", "call_w2puekrw"), toolMsgs.map { it.toolCallId })
  }

  @Test
  fun emitsToolStartedAndFinishedEvents() = runTest {
    val chat = FakeChat(listOf(::resp1, ::resp2))
    val registry = FakeRegistry { name, _, _ ->
      if (name == "set_volume") ToolOutcome.PermissionNeeded("DND", true) else ToolOutcome.Ok()
    }
    val events = mutableListOf<TurnEvent>()

    OllamaAgentEngine(chat).runTurn(request(Conversation(system), registry, onEvent = { events += it }))

    assertEquals(
      listOf(
        TurnEvent.ToolStarted("set_flashlight", JsonObject(mapOf("on" to JsonPrimitive(true)))),
        TurnEvent.ToolFinished("set_flashlight", "ok"),
        TurnEvent.ToolStarted(
          "set_volume",
          JsonObject(mapOf("stream" to JsonPrimitive("media"), "percent" to JsonPrimitive(30))),
        ),
        TurnEvent.ToolFinished("set_volume", "permission_needed"),
      ),
      events.filter { it !is TurnEvent.Thinking },
    )
  }

  @Test
  fun emitsThinkingEvent() = runTest {
    val reply = OllamaChatResult(ChatMessage(Role.ASSISTANT, "hi", thinking = "pondering"), 1, 1)
    val events = mutableListOf<TurnEvent>()
    OllamaAgentEngine(FakeChat(listOf({ reply })))
      .runTurn(request(Conversation(system), FakeRegistry(), onEvent = { events += it }))
    assertEquals(listOf<TurnEvent>(TurnEvent.Thinking("pondering")), events)
  }

  @Test
  fun conversationKeepsHistoryAcrossTurns() = runTest {
    val chat = FakeChat(listOf({ text("first answer") }, { text("second answer") }))
    val conv = Conversation(system)
    val engine = OllamaAgentEngine(chat)

    engine.runTurn(request(conv, FakeRegistry(), text = "turn on the torch"))
    val result = engine.runTurn(request(conv, FakeRegistry(), text = "and turn it off"))

    assertEquals("second answer", result.text)
    assertEquals(
      listOf(
        Role.SYSTEM to system,
        Role.USER to "turn on the torch",
        Role.ASSISTANT to "first answer",
        Role.USER to "and turn it off",
      ),
      chat.requests[1].map { it.role to it.content },
    )
    assertEquals(5, conv.messages.size)
  }

  @Test
  fun agentError_propagates() = runTest {
    val chat =
      object : OllamaChatApi {
        override suspend fun chat(model: String, messages: List<ChatMessage>, tools: List<ToolSpec>) =
          throw AgentError.Unauthorized("Ollama")
      }
    try {
      OllamaAgentEngine(chat).runTurn(request(Conversation(system), FakeRegistry()))
      fail("expected Unauthorized")
    } catch (e: AgentError.Unauthorized) {
      // expected
    }
  }
}
