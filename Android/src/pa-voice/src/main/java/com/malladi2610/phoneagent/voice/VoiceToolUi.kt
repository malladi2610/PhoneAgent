package com.malladi2610.phoneagent.voice

import com.malladi2610.phoneagent.core.tool.ToolUi
import kotlin.coroutines.cancellation.CancellationException

/** [ToolUi] for voice turns: confirmations are spoken and answered by voice. */
class VoiceToolUi(
  private val input: SpeechInput,
  private val output: SpeechOutput,
  private val sttLanguage: String,
  private val ttsLanguage: String,
  /** Suspends until the phone is unlocked (true) or the wait times out (false). */
  private val waitForUnlock: suspend () -> Boolean,
  private val ui: (VoiceUiState) -> Unit,
) : ToolUi {

  override suspend fun confirm(summary: String): Boolean {
    val question = "${summary.trimEnd('.', ' ')}. Shall I go ahead?"
    return try {
      ui(VoiceUiState.Speaking(question))
      output.speak(question, ttsLanguage)
      ui(VoiceUiState.Listening)
      val answer = input.listenOnce(sttLanguage).text
      ui(VoiceUiState.Heard(answer))
      ui(VoiceUiState.Thinking)
      YesNo.isYes(answer)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      false // could not ask or hear: never treat as consent
    }
  }

  override suspend fun requestRuntimePermission(permission: String): Boolean = false

  override suspend fun requestUnlock(): Boolean {
    val prompt = "Please unlock your phone to continue."
    ui(VoiceUiState.Speaking(prompt))
    runCatching { output.speak(prompt, ttsLanguage) }.onFailure { if (it is CancellationException) throw it }
    val unlocked = waitForUnlock() // the prompt stays on screen while we wait
    ui(VoiceUiState.Thinking)
    return unlocked
  }
}
