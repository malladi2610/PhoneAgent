package com.malladi2610.phoneagent.core.tool

/** What has to happen before a tool may run. */
enum class Gate {
  RUN,
  CONFIRM,
  UNLOCK_THEN_RUN,
  UNLOCK_THEN_CONFIRM,
}

/**
 * The PhoneAgent safety policy, in one place. Spec: M1 §2.5 (ask before outward actions) and the
 * 1b lock-screen rule (nothing personal or outward from the lock screen without unlocking).
 */
fun gateFor(risk: ToolRisk, surface: Surface): Gate {
  val locked = surface == Surface.VOICE_LOCKED
  return when (risk) {
    ToolRisk.SAFE -> Gate.RUN
    ToolRisk.SENSITIVE_READ,
    ToolRisk.OUTWARD_PREFILLED -> if (locked) Gate.UNLOCK_THEN_RUN else Gate.RUN
    ToolRisk.OUTWARD_INSTANT -> if (locked) Gate.UNLOCK_THEN_CONFIRM else Gate.CONFIRM
  }
}
