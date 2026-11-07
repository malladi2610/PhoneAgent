package com.malladi2610.phoneagent.wakeword

/**
 * Decides what a phrase hit means. Pure and single-threaded (owned by the listening loop), so the
 * pause / cooldown / one-turn-at-a-time rules are unit-tested.
 *
 * - A hit while listening asks for voice verification ([Action.VERIFY]); a score ≥ [threshold]
 *   triggers a voice turn ([Action.TRIGGER]) and hands the mic over.
 * - While a voice session is active (`PhoneAgentBus.voiceSessionActive`) the detector is paused and
 *   every hit is ignored, including a verification that was in flight when the session started.
 * - When the session ends, listening resumes with a [cooldownMs] cooldown.
 * - If a hand-off never turns into a session (no launcher, launch failed), listening resumes after
 *   [handoffTimeoutMs], or immediately via [onHandoffFailed]; both with the cooldown.
 */
class WakeGate(
  private val threshold: Float,
  private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
  private val handoffTimeoutMs: Long = DEFAULT_HANDOFF_TIMEOUT_MS,
) {
  enum class Action {
    VERIFY,
    TRIGGER,
    IGNORE,
  }

  enum class State {
    LISTENING,
    VERIFYING,
    HANDED_OFF,
    PAUSED,
  }

  var state = State.LISTENING
    private set

  private var now = 0L
  private var cooldownUntil = Long.MIN_VALUE
  private var handedOffAt = 0L

  /** Whether the service should hold the microphone and feed the detector. */
  val shouldListen: Boolean
    get() = state == State.LISTENING || state == State.VERIFYING

  fun onTick(nowMs: Long) {
    now = nowMs
    if (state == State.HANDED_OFF && now - handedOffAt >= handoffTimeoutMs) resume()
  }

  fun onKeyword(): Action {
    if (state != State.LISTENING || now < cooldownUntil) return Action.IGNORE
    state = State.VERIFYING
    return Action.VERIFY
  }

  fun onVerify(score: Float): Action {
    if (state != State.VERIFYING) return Action.IGNORE
    return if (score >= threshold) {
      state = State.HANDED_OFF
      handedOffAt = now
      Action.TRIGGER
    } else {
      state = State.LISTENING
      Action.IGNORE
    }
  }

  fun onVoiceSessionActive(active: Boolean) {
    if (active) {
      state = State.PAUSED
    } else if (state == State.PAUSED || state == State.HANDED_OFF) {
      resume()
    }
  }

  /** The voice turn could not be started; listen again (after the cooldown). */
  fun onHandoffFailed() {
    if (state == State.HANDED_OFF) resume()
  }

  private fun resume() {
    state = State.LISTENING
    cooldownUntil = now + cooldownMs
  }

  companion object {
    const val DEFAULT_COOLDOWN_MS = 2_000L
    const val DEFAULT_HANDOFF_TIMEOUT_MS = 15_000L
  }
}
