package com.malladi2610.phoneagent.wakeword

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.malladi2610.phoneagent.core.settings.PaSettings
import java.io.DataInputStream
import java.io.DataOutputStream
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

/**
 * What enrollment saved: the phrase as typed, the phrase templates (raw embedding frames of the
 * takes), the per-user match threshold and the voice profile.
 */
internal class Enrollment(
  val phrase: String,
  val templates: List<List<FloatArray>>,
  val threshold: Float,
  val profile: VoiceProfile,
) {
  init {
    require(phrase.isNotBlank()) { "Empty phrase" }
    require(templates.isNotEmpty() && templates.all { it.isNotEmpty() }) { "No templates" }
    require(threshold > 0f && threshold < 1f) { "Bad threshold $threshold" }
  }
}

/**
 * `filesDir/wakeword/enrollment.bin` (format 2). Enrollments from the old keyword-spotter build
 * (`phrase.txt` + `profile.bin`) are not usable by the new detector: they load as "not enrolled"
 * and are deleted on the next save.
 */
internal object WakeWordStore {
  private const val MAGIC = 0x50415745 // "PAWE"
  private const val VERSION = 2
  private const val FILE = "enrollment.bin"
  private val LEGACY_FILES = listOf("phrase.txt", "profile.bin")
  private const val MAX_TEMPLATES = 16
  private const val MAX_FRAMES = 200
  private const val MAX_PHRASE_CHARS = 200

  private fun dir(context: Context) = File(context.filesDir, "wakeword")

  fun load(context: Context): Enrollment? = load(dir(context))

  fun save(context: Context, enrollment: Enrollment) = save(dir(context), enrollment)

  fun load(dir: File): Enrollment? {
    val file = File(dir, FILE).takeIf { it.isFile } ?: return null
    return try {
      DataInputStream(file.inputStream().buffered()).use { input ->
        if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
        val phrase = input.readUTF()
        if (phrase.isBlank() || phrase.length > MAX_PHRASE_CHARS) return null
        val threshold = input.readFloat()
        val count = input.readInt()
        if (count !in 1..MAX_TEMPLATES) return null
        val templates =
          List(count) {
            val frames = input.readInt()
            val dim = input.readInt()
            if (frames !in 1..MAX_FRAMES || dim != SpeechEmbeddingStream.DIM) throw IOException("Corrupt template")
            List(frames) { FloatArray(dim) { input.readFloat() } }
          }
        val profile = VoiceProfile.read(input)
        if (input.read() != -1) return null // trailing garbage
        Enrollment(phrase, templates, threshold, profile)
      }
    } catch (e: IOException) {
      null
    } catch (e: IllegalArgumentException) {
      null
    }
  }

  fun save(dir: File, enrollment: Enrollment) {
    dir.mkdirs()
    val tmp = File(dir, "$FILE.tmp")
    DataOutputStream(tmp.outputStream().buffered()).use { out ->
      out.writeInt(MAGIC)
      out.writeInt(VERSION)
      out.writeUTF(enrollment.phrase.replace('\n', ' ').trim())
      out.writeFloat(enrollment.threshold)
      out.writeInt(enrollment.templates.size)
      for (t in enrollment.templates) {
        out.writeInt(t.size)
        out.writeInt(t.first().size)
        for (frame in t) for (v in frame) out.writeFloat(v)
      }
      enrollment.profile.write(out)
    }
    if (!tmp.renameTo(File(dir, FILE))) {
      tmp.delete()
      throw IOException("Could not save the wake phrase")
    }
    for (name in LEGACY_FILES) File(dir, name).delete()
  }
}
