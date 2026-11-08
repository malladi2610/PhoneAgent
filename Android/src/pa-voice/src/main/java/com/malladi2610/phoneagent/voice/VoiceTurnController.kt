package com.malladi2610.phoneagent.voice

import com.malladi2610.phoneagent.core.PhoneAgentServices
import com.malladi2610.phoneagent.core.agent.AgentError
import com.malladi2610.phoneagent.core.agent.Conversation
import com.malladi2610.phoneagent.core.agent.TurnRequest
import com.malladi2610.phoneagent.core.tool.Surface
import com.malladi2610.phoneagent.core.voice.PhoneAgentBus
import com.malladi2610.phoneagent.core.voice.VoiceTrigger
import com.malladi2610.phoneagent.voice.sarvam.SarvamWire
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** What the voice overlay / notification shows. */
sealed interface VoiceUiState {
  data object Listening : VoiceUiState

  data class Heard(val text: String) : VoiceUiState

  data object Thinking : VoiceUiState

  data class Speaking(val text: String) : VoiceUiState

  data object Done : VoiceUiState

  data class Error(val message: String) : VoiceUiState
}

/**
 * One voice turn: listen → agent (phone tools) → speak. Only one turn runs at a time; while it runs
 * [PhoneAgentBus.voiceSessionActive] is true so the wake-phrase detector releases the mic.
 */
class VoiceTurnController(
  private val input: SpeechInput,
  private val output: SpeechOutput,
  private val servicesProvider: () -> PhoneAgentServices.Installed,
  private val keyguard: () -> Boolean,
  private val ui: (VoiceUiState) -> Unit,
  /** Used to voice errors when [output] itself fails (e.g. Sarvam key rejected). */
  private val fallbackOutput: SpeechOutput? = null,
  /** Suspends until the phone is unlocked or a timeout passes (see [VoiceToolUi.requestUnlock]). */
  private val waitForUnlock: suspend () -> Boolean = { !keyguard() },
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val active = AtomicBoolean(false)
  private var conversation: Conversation? = null
  private var lastTurnEndMs = Long.MIN_VALUE

  val isActive: Boolean
    get() = active.get()

  /** Runs a turn; returns false (and does nothing) if another turn is already running. */
  suspend fun runTurn(trigger: VoiceTrigger): Boolean {
    if (!active.compareAndSet(false, true)) return false
    PhoneAgentBus.setVoiceSessionActive(true)
    var ttsLanguage = "en-IN"
    try {
      val services = servicesProvider()
      val settings = services.settings
      ttsLanguage = settings.ttsLanguage
      val model = settings.voiceModel?.takeIf { it.isNotBlank() }
      if (model == null) {
        fail(CHOOSE_MODEL, ttsLanguage)
        return true
      }

      ui(VoiceUiState.Listening)
      val heard = input.listenOnce(settings.sttLanguage)
      ttsLanguage = SarvamWire.ttsLanguageFor(heard.language, settings.ttsLanguage)
      if (heard.text.isBlank()) {
        ui(VoiceUiState.Speaking(DIDNT_CATCH))
        speakSafely(DIDNT_CATCH, ttsLanguage)
        ui(VoiceUiState.Done)
        return true
      }
      ui(VoiceUiState.Heard(heard.text))
      ui(VoiceUiState.Thinking)

      val surface = if (keyguard()) Surface.VOICE_LOCKED else Surface.VOICE_UNLOCKED
      val toolUi = VoiceToolUi(input, output, settings.sttLanguage, ttsLanguage, waitForUnlock, ui)
      val result =
        services.agentEngine.runTurn(
          TurnRequest(
            conversation = conversationForNow(),
            userText = heard.text,
            model = model,
            surface = surface,
            tools = services.phoneTools,
            ui = toolUi,
          )
        )
      val reply = result.text.ifBlank { "Done." }
      ui(VoiceUiState.Speaking(reply))
      output.speak(reply, ttsLanguage)
      ui(VoiceUiState.Done)
    } catch (e: CancellationException) {
      throw e
    } catch (e: AgentError) {
      fail(e.userMessage, ttsLanguage)
    } catch (e: MicUnavailableException) {
      fail(MIC_UNAVAILABLE, ttsLanguage)
    } catch (e: Exception) {
      fail(SOMETHING_WRONG, ttsLanguage)
    } finally {
      lastTurnEndMs = clock()
      PhoneAgentBus.setVoiceSessionActive(false)
      active.set(false)
    }
    return true
  }

  private fun conversationForNow(): Conversation {
    val existing = conversation
    if (existing != null && clock() - lastTurnEndMs <= FOLLOW_UP_WINDOW_MS) return existing
    return Conversation(systemPrompt()).also { conversation = it }
  }

  private suspend fun fail(message: String, language: String) {
    ui(VoiceUiState.Error(message))
    withContext(NonCancellable) { speakSafely(message, language) }
  }

  private suspend fun speakSafely(text: String, language: String) {
    try {
      output.speak(text, language)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      try {
        fallbackOutput?.speak(text, language)
      } catch (e2: CancellationException) {
        throw e2
      } catch (_: Exception) {
        // The message is already on screen (VoiceUiState.Error); nothing else we can do.
      }
    }
  }

  private fun systemPrompt(): String =
    """
    You are PhoneAgent, a voice assistant running on the user's Android phone.
    Your reply is spoken aloud: answer in one or two short spoken sentences, plain text only,
    with no markdown, lists, emoji, or URLs. Reply in the language the user spoke.
    Use the phone tools to act; never claim you did something a tool did not confirm.
    If a tool reports permission_needed, tell the user to open PhoneAgent setup to allow it.
    If a tool reports needs_unlock, tell the user to unlock the phone and ask again.
    If a tool reports denied_by_user, acknowledge briefly and don't retry.
    The current local time is ${ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm z"))}.
    """
      .trimIndent()

  companion object {
    const val FOLLOW_UP_WINDOW_MS = 2 * 60 * 1000L
    const val CHOOSE_MODEL = "Choose a model in PhoneAgent setup."
    const val DIDNT_CATCH = "Sorry, I didn't catch that."
    const val MIC_UNAVAILABLE = "I can't use the microphone. Allow microphone access in PhoneAgent setup."
    const val SOMETHING_WRONG = "Sorry, something went wrong."
  }
}
