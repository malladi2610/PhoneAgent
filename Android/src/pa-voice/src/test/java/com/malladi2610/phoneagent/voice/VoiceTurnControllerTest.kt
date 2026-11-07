package com.malladi2610.phoneagent.voice

import com.malladi2610.phoneagent.core.agent.AgentError
import com.malladi2610.phoneagent.core.tool.Surface
import com.malladi2610.phoneagent.core.voice.PhoneAgentBus
import com.malladi2610.phoneagent.core.voice.VoiceTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoiceTurnControllerTest {
  private val settings = testSettings().apply { voiceModel = "gpt-oss:120b" }
  private val engine = FakeEngine()
  private val output = FakeOutput()
  private val fallback = FakeOutput()
  private val states = mutableListOf<VoiceUiState>()
  private var locked = false
  private var now = 1_000_000L

  @Before
  fun setUp() {
    PhoneAgentBus.setVoiceSessionActive(false)
  }

  @After
  fun tearDown() {
    PhoneAgentBus.setVoiceSessionActive(false)
  }

  private fun controller(input: SpeechInput) =
    VoiceTurnController(
      input = input,
      output = output,
      servicesProvider = { services(engine, settings) },
      keyguard = { locked },
      ui = { states += it },
      fallbackOutput = fallback,
      clock = { now },
    )

  @Test
  fun happyPath_listensRunsAgentSpeaksReply() = runTest {
    engine.reply = { FakeEngine.ok("Your battery is at 80 percent.") }
    val input = FakeInput(SttResult("what's my battery", "en-IN"))

    controller(input).runTurn(VoiceTrigger.ASSISTANT_BUTTON)

    assertEquals(listOf("auto"), input.languages)
    val req = engine.requests.single()
    assertEquals("what's my battery", req.userText)
    assertEquals("gpt-oss:120b", req.model)
    assertSame(NoTools, req.tools)
    assertEquals(Surface.VOICE_UNLOCKED, req.surface)
    assertTrue(req.conversation.messages.first().content.contains("spoken"))
    assertEquals(listOf("Your battery is at 80 percent." to "en-IN"), output.spoken)
    assertEquals(
      listOf(
        VoiceUiState.Listening,
        VoiceUiState.Heard("what's my battery"),
        VoiceUiState.Thinking,
        VoiceUiState.Speaking("Your battery is at 80 percent."),
        VoiceUiState.Done,
      ),
      states,
    )
  }

  @Test
  fun detectedLanguage_usedForTts_whenSupported() = runTest {
    controller(FakeInput(SttResult("torch on karo", "hi-IN"))).runTurn(VoiceTrigger.WAKE_PHRASE)

    assertEquals("hi-IN", output.spoken.single().second)
  }

  @Test
  fun unsupportedDetectedLanguage_fallsBackToSettingsTtsLanguage() = runTest {
    settings.ttsLanguage = "te-IN"

    controller(FakeInput(SttResult("something", "ur-IN"))).runTurn(VoiceTrigger.WAKE_PHRASE)

    assertEquals("te-IN", output.spoken.single().second)
  }

  @Test
  fun emptyTranscript_speaksDidntCatchThat_noAgentCall() = runTest {
    controller(FakeInput(SttResult("  ", null))).runTurn(VoiceTrigger.WAKE_PHRASE)

    assertTrue(engine.requests.isEmpty())
    assertEquals("Sorry, I didn't catch that.", output.spoken.single().first)
    assertEquals(VoiceUiState.Done, states.last())
  }

  @Test
  fun agentError_isSpoken() = runTest {
    engine.reply = { throw AgentError.Unauthorized("Ollama") }

    controller(FakeInput(SttResult("turn on the torch", "en-IN"))).runTurn(VoiceTrigger.WAKE_PHRASE)

    val msg = AgentError.Unauthorized("Ollama").userMessage
    assertEquals(listOf(msg to "en-IN"), output.spoken)
    assertTrue(states.contains(VoiceUiState.Error(msg)))
  }

  @Test
  fun sarvamRejected_errorShownAndSpokenViaFallback() = runTest {
    val input =
      object : SpeechInput {
        override suspend fun listenOnce(language: String): SttResult = throw AgentError.Unauthorized("Sarvam")
      }
    output.failWith = AgentError.Unauthorized("Sarvam")

    controller(input).runTurn(VoiceTrigger.WAKE_PHRASE)

    val msg = AgentError.Unauthorized("Sarvam").userMessage
    assertTrue(states.contains(VoiceUiState.Error(msg)))
    assertEquals(msg, fallback.spoken.single().first)
    assertTrue(engine.requests.isEmpty())
  }

  @Test
  fun micUnavailable_isExplained() = runTest {
    val input =
      object : SpeechInput {
        override suspend fun listenOnce(language: String): SttResult = throw MicUnavailableException("denied")
      }

    controller(input).runTurn(VoiceTrigger.WAKE_PHRASE)

    assertTrue(states.last() is VoiceUiState.Error)
    assertTrue(output.spoken.single().first.contains("microphone"))
  }

  @Test
  fun missingVoiceModel_speaksChooseModel() = runTest {
    settings.voiceModel = null
    val input = FakeInput(SttResult("hello", "en-IN"))

    controller(input).runTurn(VoiceTrigger.ASSISTANT_BUTTON)

    assertTrue(input.languages.isEmpty()) // didn't make the user talk in vain
    assertTrue(engine.requests.isEmpty())
    assertEquals("Choose a model in PhoneAgent setup.", output.spoken.single().first)
    assertEquals(VoiceUiState.Error("Choose a model in PhoneAgent setup."), states.last())
  }

  @Test
  fun busFlag_trueDuringTurn_falseAfter_evenOnError() = runTest {
    var flagWhileListening = false
    val input = FakeInput(SttResult("hi", "en-IN")).apply {
      onListen = { flagWhileListening = PhoneAgentBus.voiceSessionActive.value }
    }
    engine.reply = { throw IllegalStateException("boom") }

    controller(input).runTurn(VoiceTrigger.WAKE_PHRASE)

    assertTrue(flagWhileListening)
    assertFalse(PhoneAgentBus.voiceSessionActive.value)
    assertTrue(states.last() is VoiceUiState.Error)
  }

  @Test
  fun busFlag_falseAfterCancellation() = runTest {
    val gate = CompletableDeferred<Unit>()
    val input = FakeInput(SttResult("hi", "en-IN")).apply { onListen = { gate.await() } }
    val c = controller(input)

    val job = launch { c.runTurn(VoiceTrigger.WAKE_PHRASE) }
    runCurrent()
    assertTrue(PhoneAgentBus.voiceSessionActive.value)
    job.cancel()
    runCurrent()

    assertFalse(PhoneAgentBus.voiceSessionActive.value)
    assertFalse(c.isActive)
  }

  @Test
  fun secondTrigger_whileActive_isIgnored() = runTest {
    val gate = CompletableDeferred<Unit>()
    val input = FakeInput(SttResult("first", "en-IN"), SttResult("second", "en-IN")).apply {
      onListen = { gate.await() }
    }
    val c = controller(input)

    val first = launch { c.runTurn(VoiceTrigger.WAKE_PHRASE) }
    runCurrent()
    assertTrue(c.isActive)
    val accepted = c.runTurn(VoiceTrigger.ASSISTANT_BUTTON) // returns immediately
    gate.complete(Unit)
    first.join()

    assertFalse(accepted)
    assertEquals(1, input.languages.size)
    assertEquals(listOf("first"), engine.requests.map { it.userText })
    assertFalse(c.isActive)
  }

  @Test
  fun locked_usesVoiceLockedSurface() = runTest {
    locked = true

    controller(FakeInput(SttResult("turn on the torch", "en-IN"))).runTurn(VoiceTrigger.WAKE_PHRASE)

    assertEquals(Surface.VOICE_LOCKED, engine.requests.single().surface)
    assertTrue(engine.requests.single().ui is VoiceToolUi)
  }

  @Test
  fun followUpWithinTwoMinutes_reusesConversation() = runTest {
    val input =
      FakeInput(SttResult("turn on the torch", "en-IN"), SttResult("and turn it off", "en-IN"), SttResult("hi", "en-IN"))
    val c = controller(input)

    c.runTurn(VoiceTrigger.WAKE_PHRASE)
    now += 119_000
    c.runTurn(VoiceTrigger.WAKE_PHRASE)
    now += 121_000
    c.runTurn(VoiceTrigger.WAKE_PHRASE)

    val (a, b, d) = engine.requests
    assertSame(a.conversation, b.conversation)
    assertNotSame(b.conversation, d.conversation)
  }
}
