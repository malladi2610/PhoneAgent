package com.malladi2610.phoneagent.voice

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Waits for ACTION_USER_PRESENT (or the keyguard going away), up to a timeout. */
class UnlockWaiter(private val context: Context) {
  private val keyguardManager = context.getSystemService(KeyguardManager::class.java)
  private val _waiting = MutableStateFlow(false)

  /** True while a turn is waiting for the user to unlock (the overlay lets touches through). */
  val waiting: StateFlow<Boolean> = _waiting

  fun isLocked(): Boolean = keyguardManager?.isKeyguardLocked == true

  suspend fun await(timeoutMs: Long = 30_000): Boolean {
    if (!isLocked()) return true
    val present = CompletableDeferred<Unit>()
    val receiver =
      object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
          present.complete(Unit)
        }
      }
    context.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_NOT_EXPORTED)
    _waiting.value = true
    try {
      return withTimeoutOrNull(timeoutMs) {
        coroutineScope {
          // Backup poll: some unlock paths (e.g. Smart Lock) don't broadcast USER_PRESENT promptly.
          val poll = launch {
            while (isLocked()) delay(500)
            present.complete(Unit)
          }
          present.await()
          poll.cancel()
        }
        true
      } ?: false
    } finally {
      _waiting.value = false
      runCatching { context.unregisterReceiver(receiver) }
    }
  }
}
