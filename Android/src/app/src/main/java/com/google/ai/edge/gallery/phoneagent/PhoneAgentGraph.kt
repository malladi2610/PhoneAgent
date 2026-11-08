package com.google.ai.edge.gallery.phoneagent

import android.app.Application
import android.util.Log
import com.malladi2610.phoneagent.core.PhoneAgentServices
import com.malladi2610.phoneagent.core.ollama.OllamaAgentEngine
import com.malladi2610.phoneagent.core.ollama.OllamaClient
import com.malladi2610.phoneagent.core.secrets.KeystoreSecretStore
import com.malladi2610.phoneagent.core.secrets.SecretKey
import com.malladi2610.phoneagent.core.settings.PaSettings
import com.malladi2610.phoneagent.tools.JsonlActionLog
import com.malladi2610.phoneagent.tools.PhoneToolRunner
import com.malladi2610.phoneagent.tools.createPhoneTools
import com.malladi2610.phoneagent.voice.PaVoice
import com.malladi2610.phoneagent.wakeword.WakeWord
import java.io.File
import okhttp3.OkHttpClient

/**
 * Wires the PhoneAgent modules together once per process (plan Task I2). Called from
 * GalleryApplication.onCreate — the only line PhoneAgent adds there.
 */
object PhoneAgentGraph {
  private const val TAG = "PhoneAgentGraph"

  fun install(app: Application) {
    if (PhoneAgentServices.isInstalled()) return
    val secrets = KeystoreSecretStore(app)
    val settings = PaSettings(app)
    val actionLog =
      JsonlActionLog(
        dir = File(app.filesDir, "action_log"),
        redact =
          JsonlActionLog.redactValues {
            listOf(secrets.get(SecretKey.OLLAMA_API_KEY), secrets.get(SecretKey.SARVAM_API_KEY))
          },
      )
    val ollama = OllamaClient(OkHttpClient(), { secrets.get(SecretKey.OLLAMA_API_KEY) })
    PhoneAgentServices.install(
      PhoneAgentServices.Installed(
        agentEngine = OllamaAgentEngine(ollama),
        phoneTools = PhoneToolRunner(createPhoneTools(app), actionLog),
        secrets = secrets,
        settings = settings,
        actionLog = actionLog,
      )
    )
    PaVoice.install(app)
    try {
      WakeWord.start(app)
    } catch (e: Exception) {
      // Android may refuse a microphone foreground service when the process starts in the
      // background; the wake-word module posts a "tap to resume" notification in that case.
      Log.w(TAG, "Wake phrase not started at app start: ${e.javaClass.simpleName}")
    }
  }
}
