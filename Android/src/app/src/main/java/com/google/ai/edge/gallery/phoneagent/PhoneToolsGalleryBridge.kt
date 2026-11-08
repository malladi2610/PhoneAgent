package com.google.ai.edge.gallery.phoneagent

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.tools.SkillProgressToolAction
import com.google.ai.edge.gallery.tools.ToolDefinition
import com.google.ai.edge.gallery.tools.ToolExecutionContext
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.malladi2610.phoneagent.core.ChatTurnInfo
import com.malladi2610.phoneagent.core.PhoneAgentServices
import com.malladi2610.phoneagent.core.tool.Surface
import com.malladi2610.phoneagent.core.tool.ToolContext
import com.malladi2610.phoneagent.core.tool.ToolOutcome
import com.malladi2610.phoneagent.core.tool.ToolRegistry
import com.malladi2610.phoneagent.tools.JsonlActionLog
import com.malladi2610.phoneagent.tools.OpenApiJson
import com.malladi2610.phoneagent.tools.PhoneToolRunner
import com.malladi2610.phoneagent.tools.createPhoneTools
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val TAG = "PaPhoneToolsBridge"

/**
 * Exposes the gated, logged phone tools ([runner], normally `PhoneAgentServices.phoneTools`) to
 * Gallery's Agent Chat as LiteRT-LM [OpenApiTool]s.
 *
 * It is a [ToolDefinition] only so Gallery's dispatcher calls [onAttach] with the chat's action
 * channel; it has no `@Tool` methods. `AgentToolsImpl.getLiteRtToolProviders()` must append
 * [providers] and skip `tool(this)`.
 *
 * Every call runs with [Surface.CHAT] and the turn metadata in [ChatTurnInfo] (set by the Ollama
 * runtime). Confirm cards and permission prompts go through [GalleryChatToolUi].
 */
class PhoneToolsGalleryBridge(private val runner: ToolRegistry) : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  fun providers(): List<ToolProvider> =
    runner.specs().map { spec ->
      tool(
        object : OpenApiTool {
          override fun getToolDescriptionJsonString(): String = OpenApiJson.description(spec)

          override fun execute(paramsJsonString: String): String =
            executeTool(spec.name, paramsJsonString)
        }
      )
    }

  /** Synchronous entry point for LiteRT's ToolManager. Never throws. */
  fun executeTool(name: String, paramsJson: String): String {
    val channel = executionContext?.actionChannel
    return try {
      runBlocking(Dispatchers.IO) {
        channel?.trySend(
          SkillProgressToolAction(
            label = "Running phone tool \"$name\"",
            inProgress = true,
            addItemTitle = "Phone tool: \"$name\"",
            addItemDescription = "- Input: $paramsJson",
          )
        )
        val ctx =
          ToolContext(
            surface = Surface.CHAT,
            turnId = ChatTurnInfo.turnId,
            userRequest = ChatTurnInfo.userRequest,
            model = ChatTurnInfo.model,
            step = ChatTurnInfo.step,
            ui = GalleryChatToolUi(channel, toolName = name),
          )
        val result = OpenApiJson.execute(runner, name, paramsJson, ctx)
        val ok = result.startsWith("{\"status\":\"ok\"")
        channel?.trySend(
          SkillProgressToolAction(
            label =
              if (ok) "Phone tool \"$name\" done" else "Phone tool \"$name\" didn't complete",
            inProgress = ok,
            addItemTitle = "Phone tool \"$name\" result",
            addItemDescription = result,
          )
        )
        result
      }
    } catch (t: Throwable) {
      Log.w(TAG, "phone tool $name failed: ${t.javaClass.simpleName}")
      ToolOutcome.Error(t.message ?: "phone tool failed").toJson().toString()
    }
  }

  companion object {
    /**
     * Uses the app-wide phone tools when `PhoneAgentGraph` has installed [PhoneAgentServices];
     * otherwise builds a local runner (ActionLog without key redaction, since no SecretStore is
     * reachable — only happens if the graph isn't installed).
     */
    fun create(context: Context): PhoneToolsGalleryBridge {
      val registry =
        if (PhoneAgentServices.isInstalled()) PhoneAgentServices.get().phoneTools
        else {
          Log.w(TAG, "PhoneAgentServices not installed; using a local phone-tool runner")
          val app = context.applicationContext
          PhoneToolRunner(
            createPhoneTools(app),
            JsonlActionLog(File(app.filesDir, "action_log"), redact = { it }),
          )
        }
      return PhoneToolsGalleryBridge(registry)
    }
  }
}
