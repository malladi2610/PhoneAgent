package com.google.ai.edge.gallery.phoneagent

import com.google.ai.edge.gallery.tools.ConfirmToolCallAction
import com.google.ai.edge.gallery.tools.RequestPermissionToolAction
import com.google.ai.edge.gallery.tools.ToolAction
import com.malladi2610.phoneagent.core.tool.ToolUi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [ToolUi] for Agent Chat: prompts go to `AgentChatScreen` through Gallery's tool action channel.
 * - confirm → [ConfirmToolCallAction] (Allow/Deny dialog)
 * - runtime permission → [RequestPermissionToolAction] (system prompt)
 * - unlock → always true (chat is typed on an unlocked phone)
 *
 * With no channel (no chat screen attached) or no answer within [timeoutMs], prompts fail closed:
 * confirm = denied, permission = not granted.
 */
class GalleryChatToolUi(
  private val actionChannel: SendChannel<ToolAction>?,
  private val toolName: String = "",
  private val timeoutMs: Long = PROMPT_TIMEOUT_MS,
) : ToolUi {
  override suspend fun confirm(summary: String): Boolean {
    val channel = actionChannel ?: return false
    val action = ConfirmToolCallAction(toolName = toolName, summary = summary)
    if (channel.trySend(action).isFailure) return false
    return withTimeoutOrNull(timeoutMs) { action.result.await() } ?: false
  }

  override suspend fun requestRuntimePermission(permission: String): Boolean {
    val channel = actionChannel ?: return false
    val action = RequestPermissionToolAction(permission = permission)
    if (channel.trySend(action).isFailure) return false
    return withTimeoutOrNull(timeoutMs) { action.result.await() } ?: false
  }

  override suspend fun requestUnlock(): Boolean = true

  companion object {
    const val PROMPT_TIMEOUT_MS = 120_000L
  }
}
