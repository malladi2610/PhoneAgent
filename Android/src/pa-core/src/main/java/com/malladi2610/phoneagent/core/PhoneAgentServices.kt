package com.malladi2610.phoneagent.core

import com.malladi2610.phoneagent.core.agent.AgentEngine
import com.malladi2610.phoneagent.core.log.ActionLog
import com.malladi2610.phoneagent.core.secrets.SecretStore
import com.malladi2610.phoneagent.core.settings.PaSettings
import com.malladi2610.phoneagent.core.tool.ToolRegistry

/**
 * Process-wide service locator. The app installs it once in Application.onCreate
 * (`PhoneAgentGraph.install`), so modules that depend only on pa-core (pa-voice, pa-wakeword)
 * can reach implementations that live in other modules.
 */
object PhoneAgentServices {
  @Volatile private var installed: Installed? = null

  class Installed(
    val agentEngine: AgentEngine,
    /** Phone tools only (gated, logged). Used by voice turns. */
    val phoneTools: ToolRegistry,
    val secrets: SecretStore,
    val settings: PaSettings,
    val actionLog: ActionLog,
  )

  fun install(services: Installed) {
    installed = services
  }

  fun get(): Installed =
    installed ?: error("PhoneAgentServices not installed; call PhoneAgentGraph.install() first")

  fun isInstalled(): Boolean = installed != null
}

/**
 * Turn metadata for the Agent Chat path. Gallery executes tools through LiteRT-LM's synchronous
 * ToolManager, which carries no context, so the Ollama runtime sets this before executing tool
 * calls and the phone-tool bridge reads it to build a ToolContext and ActionLog entry.
 */
object ChatTurnInfo {
  @Volatile var turnId: String = ""
  @Volatile var userRequest: String = ""
  @Volatile var model: String = ""
  @Volatile var step: Int = 0
}

