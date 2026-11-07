package com.malladi2610.phoneagent.wakeword

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.malladi2610.phoneagent.core.settings.PaSettings
import java.io.File
import java.io.IOException

/** Entry points for the app: start/stop the always-listening wake-phrase service. */
object WakeWord {
  private const val TAG = "PaWakeWord"

  /**
   * Starts listening if the owner is enrolled, RECORD_AUDIO is granted and
   * `PaSettings.wakeWordEnabled` is true. Safe to call from `Application.onCreate`: when Android
   * refuses a microphone service from the background, a "Tap to resume listening" notification is
   * posted instead.
   *
   * @return true if the service was started (or is already running).
   */
  fun start(context: Context): Boolean {
    val app = context.applicationContext
    if (WakeWordService.isRunning) return true
    if (!PaSettings(app).wakeWordEnabled) return false
    if (!isEnrolled(app)) {
      Log.i(TAG, "Not starting: no wake phrase enrolled")
      return false
    }
    if (!hasMicPermission(app)) {
      Log.i(TAG, "Not starting: RECORD_AUDIO not granted")
      return false
    }
    return try {
      app.startForegroundService(Intent(app, WakeWordService::class.java))
      true
    } catch (e: ForegroundServiceStartNotAllowedException) {
      Log.i(TAG, "Background start not allowed; asking the user to resume")
      WakeNotifications.postResume(app)
      false
    } catch (e: IllegalStateException) {
      WakeNotifications.postResume(app)
      false
    } catch (e: SecurityException) {
      Log.w(TAG, "Could not start wake-word service", e)
      false
    }
  }

  /** Stops listening (does not change `PaSettings.wakeWordEnabled`). */
  fun stop(context: Context) {
    val app = context.applicationContext
    app.stopService(Intent(app, WakeWordService::class.java))
  }

  fun isEnrolled(context: Context): Boolean = WakeWordStore.load(context.applicationContext) != null

  /** The enrolled phrase as typed by the user, or null. */
  fun enrolledPhrase(context: Context): String? = WakeWordStore.load(context.applicationContext)?.phrase

  val isRunning: Boolean
    get() = WakeWordService.isRunning

  internal fun hasMicPermission(context: Context) =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

/** What enrollment saved: `filesDir/wakeword/{profile.bin,phrase.txt}`. */
internal data class Enrollment(val phrase: String, val keywordLine: String, val profile: VoiceProfile)

internal object WakeWordStore {
  private fun dir(context: Context) = File(context.filesDir, "wakeword")

  private fun profileFile(context: Context) = File(dir(context), "profile.bin")

  /** Line 1: the phrase as typed; line 2: its encoded keyword line. */
  private fun phraseFile(context: Context) = File(dir(context), "phrase.txt")

  fun load(context: Context): Enrollment? {
    return try {
      val lines = phraseFile(context).takeIf { it.isFile }?.readLines() ?: return null
      val phrase = lines.getOrNull(0)?.trim().orEmpty()
      val keywordLine = lines.getOrNull(1)?.trim().orEmpty()
      if (phrase.isEmpty() || !keywordLine.contains('@')) return null
      val profileFile = profileFile(context).takeIf { it.isFile } ?: return null
      Enrollment(phrase, keywordLine, VoiceProfile.load(profileFile))
    } catch (e: IOException) {
      null
    } catch (e: IllegalArgumentException) {
      null
    }
  }

  fun save(context: Context, enrollment: Enrollment) {
    dir(context).mkdirs()
    enrollment.profile.save(profileFile(context))
    val tmp = File(dir(context), "phrase.txt.tmp")
    tmp.writeText(enrollment.phrase.replace('\n', ' ') + "\n" + enrollment.keywordLine + "\n")
    if (!tmp.renameTo(phraseFile(context))) throw IOException("Could not save the wake phrase")
  }
}
