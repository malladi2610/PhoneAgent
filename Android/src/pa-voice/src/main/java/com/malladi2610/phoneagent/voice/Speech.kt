package com.malladi2610.phoneagent.voice

/** One recognised utterance. [language] is a BCP-47 code (e.g. `hi-IN`) when known. */
data class SttResult(val text: String, val language: String?)

/** Records one utterance and transcribes it. Empty [SttResult.text] = nothing heard. */
interface SpeechInput {
  /** [language] is `auto` or a Sarvam code like `en-IN`. Throws AgentError / MicUnavailableException. */
  suspend fun listenOnce(language: String): SttResult
}

/** Speaks [text] and returns once playback has finished. Throws AgentError on service failures. */
interface SpeechOutput {
  suspend fun speak(text: String, language: String)
}

/** The microphone couldn't be opened (permission missing, or held by another app). */
class MicUnavailableException(message: String) : Exception(message)
