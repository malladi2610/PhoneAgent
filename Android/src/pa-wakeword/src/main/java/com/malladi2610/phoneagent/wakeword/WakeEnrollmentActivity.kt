package com.malladi2610.phoneagent.wakeword

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.malladi2610.phoneagent.core.settings.PaSettings
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wake-phrase setup: choose a phrase (checked against the lexicon), record 5 takes for voice
 * match, test live hits and scores, enable the always-listening service, and lift battery limits.
 */
class WakeEnrollmentActivity : ComponentActivity() {
  private val model: WakeEnrollmentViewModel by viewModels()
  private var afterPermission: (() -> Unit)? = null

  private val permissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
      val action = afterPermission
      afterPermission = null
      if (result[Manifest.permission.RECORD_AUDIO] == true || WakeWord.hasMicPermission(this)) {
        action?.invoke()
      } else {
        model.status = "Microphone permission is needed for the wake phrase."
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      val dark = androidx.compose.foundation.isSystemInDarkTheme()
      val context = LocalContext.current
      MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)) {
        Surface(Modifier.fillMaxSize()) {
          EnrollmentScreen(
            model = model,
            withMic = ::withMicPermission,
            onBattery = ::openBatterySettings,
          )
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    model.refresh()
  }

  private fun withMicPermission(action: () -> Unit) {
    val wanted = buildList {
      if (!WakeWord.hasMicPermission(this@WakeEnrollmentActivity)) add(Manifest.permission.RECORD_AUDIO)
      if (
        Build.VERSION.SDK_INT >= 33 &&
          checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
      ) {
        add(Manifest.permission.POST_NOTIFICATIONS)
      }
    }
    if (wanted.isEmpty() || (wanted == listOf(Manifest.permission.POST_NOTIFICATIONS) && model.askedNotifications)) {
      action()
      return
    }
    model.askedNotifications = true
    afterPermission = action
    permissionLauncher.launch(wanted.toTypedArray())
  }

  private fun openBatterySettings() {
    val request =
      Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
    try {
      startActivity(request)
    } catch (e: ActivityNotFoundException) {
      runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
  }
}

internal class WakeEnrollmentViewModel(app: Application) : AndroidViewModel(app) {
  enum class Mode {
    IDLE,
    LOADING,
    ENROLLING,
    TESTING,
  }

  private val settings = PaSettings(app)

  var phrase by mutableStateOf(WakeWord.enrolledPhrase(app) ?: DEFAULT_PHRASE)
  var phraseError by mutableStateOf<String?>(null)
  var mode by mutableStateOf(Mode.IDLE)
  var takes by mutableStateOf(0)
  var status by mutableStateOf<String?>(null)
  var enrolledPhrase by mutableStateOf<String?>(null)
  var threshold by mutableStateOf<Float?>(null)
  var enabled by mutableStateOf(settings.wakeWordEnabled)
  var running by mutableStateOf(WakeWord.isRunning)
  var batteryUnrestricted by mutableStateOf(false)
  val testLog = mutableStateListOf<String>()
  var askedNotifications = false

  private var job: Job? = null

  init {
    refresh()
  }

  fun refresh() {
    val app = getApplication<Application>()
    val enrollment = WakeWordStore.load(app)
    enrolledPhrase = enrollment?.phrase
    threshold = enrollment?.profile?.threshold()
    enabled = settings.wakeWordEnabled
    running = WakeWord.isRunning
    batteryUnrestricted =
      app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
  }

  fun onPhraseChange(value: String) {
    phrase = value
    phraseError = null
  }

  fun enroll() {
    if (job?.isActive == true) return
    job =
      viewModelScope.launch {
        mode = Mode.LOADING
        status = "Checking the phrase…"
        val typed = phrase.trim()
        val keywordLine =
          encode(typed).getOrElse {
            phraseError = it.message
            status = null
            mode = Mode.IDLE
            return@launch
          }
        val embeddings = mutableListOf<FloatArray>()
        takes = 0
        runCapture(keywordLine, Mode.ENROLLING, "Say “$typed” ($TAKES times)") { embedding ->
          if (embedding == null) {
            status = "That one was too short, say it again."
            return@runCapture true
          }
          embeddings += embedding
          takes = embeddings.size
          status = if (takes < TAKES) "Got it. Again (${takes + 1} of $TAKES)." else "Saving…"
          takes < TAKES
        }
        if (embeddings.size == TAKES) {
          val profile = VoiceProfile(embeddings).withoutOutliers()
          withContext(Dispatchers.IO) {
            WakeWordStore.save(getApplication(), Enrollment(typed, keywordLine, profile))
          }
          status =
            "Enrolled “$typed”. Kept ${profile.size} of $TAKES takes; " +
              "voice-match threshold ${fmt(profile.threshold())}."
        }
        refresh()
      }
  }

  fun test() {
    if (job?.isActive == true) return
    val enrollment = WakeWordStore.load(getApplication()) ?: return
    val threshold = enrollment.profile.threshold()
    testLog.clear()
    job =
      viewModelScope.launch {
        runCapture(enrollment.keywordLine, Mode.TESTING, "Say “${enrollment.phrase}”…") { embedding ->
          val line =
            if (embedding == null) {
              "Phrase heard · too short to match the voice"
            } else {
              val score = enrollment.profile.score(embedding)
              val verdict = if (score >= threshold) "would wake" else "ignored (voice didn't match)"
              "Phrase heard · voice ${fmt(score)} vs ${fmt(threshold)} → $verdict"
            }
          testLog.add(0, line)
          if (testLog.size > 20) testLog.removeAt(testLog.lastIndex)
          true
        }
      }
  }

  fun stop() {
    job?.cancel()
  }

  fun changeEnabled(on: Boolean) {
    val app = getApplication<Application>()
    settings.wakeWordEnabled = on
    if (on) {
      if (!WakeWord.start(app)) status = "Couldn't start listening. Enroll first and allow the microphone."
    } else {
      WakeWord.stop(app)
    }
    refresh()
  }

  /** Validates [typed] against the lexicon (reads only the needed words from en.phone). */
  private suspend fun encode(typed: String): Result<String> =
    withContext(Dispatchers.IO) {
      val words = WakePhraseEncoder.words(typed)
      if (words.isEmpty()) return@withContext WakePhraseEncoder(Lexicon.parse(emptySequence())).encode(typed)
      val lexicon =
        try {
          Lexicon.load(getApplication<Application>().assets.open(Lexicon.ASSET_PATH), words.toSet())
        } catch (e: IOException) {
          return@withContext Result.failure(IOException("The wake-word dictionary is missing from the app."))
        }
      WakePhraseEncoder(lexicon).encode(typed)
    }

  /**
   * Stops the background service, listens with [keywordLine], and calls [onHit] (main thread) with
   * the speaker embedding of each detection until it returns false or the job is cancelled. Restarts
   * the service afterwards if it is enabled.
   */
  private suspend fun runCapture(
    keywordLine: String,
    captureMode: Mode,
    prompt: String,
    onHit: (FloatArray?) -> Boolean,
  ) {
    val app = getApplication<Application>()
    WakeWord.stop(app)
    mode = Mode.LOADING
    status = "Loading the wake-word models…"
    try {
      withContext(Dispatchers.IO) {
        val engine = WakeWordEngine.create(app)
        try {
          engine.newDetector(keywordLine).use { detector ->
            MicCapture.open().use { mic ->
              val ring = RingBuffer((WakePipeline.WINDOW_SECONDS * WakePipeline.SAMPLE_RATE).toInt())
              withContext(Dispatchers.Main) {
                mode = captureMode
                status = prompt
              }
              while (isActive) {
                val chunk = mic.read() ?: throw IOException("The microphone stopped delivering audio.")
                ring.write(chunk)
                if (detector.accept(chunk)) {
                  val embedding = engine.embed(SpeechTrim.trim(ring.snapshot(), WakePipeline.SAMPLE_RATE))
                  ring.clear()
                  if (!withContext(Dispatchers.Main) { onHit(embedding) }) break
                }
              }
            }
          }
        } finally {
          engine.close()
        }
      }
    } catch (e: WakeWordUnavailableException) {
      status = "Wake phrase isn't available on this device: ${e.message}"
    } catch (e: IOException) {
      status = e.message ?: "Microphone unavailable."
    } catch (e: SecurityException) {
      status = "Microphone permission is needed for the wake phrase."
    } catch (e: IllegalArgumentException) {
      status = "The wake-word engine rejected this phrase. Try a different one."
    } finally {
      mode = Mode.IDLE
      if (settings.wakeWordEnabled) WakeWord.start(app)
      refresh()
    }
  }

  companion object {
    const val DEFAULT_PHRASE = "Hey Jarvis"
    const val TAKES = 5

    fun fmt(v: Float) = String.format(Locale.ROOT, "%.2f", v)
  }
}

@Composable
private fun EnrollmentScreen(
  model: WakeEnrollmentViewModel,
  withMic: (() -> Unit) -> Unit,
  onBattery: () -> Unit,
) {
  val busy = model.mode != WakeEnrollmentViewModel.Mode.IDLE
  Column(
    Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Wake phrase", style = MaterialTheme.typography.headlineSmall)
    Text(
      "Say your phrase to start PhoneAgent hands-free, even on the lock screen. Voice match cuts " +
        "down false wakes from other voices; it is not a security feature.",
      style = MaterialTheme.typography.bodyMedium,
    )

    OutlinedTextField(
      value = model.phrase,
      onValueChange = model::onPhraseChange,
      label = { Text("Phrase") },
      singleLine = true,
      enabled = !busy,
      isError = model.phraseError != null,
      supportingText = {
        Text(model.phraseError ?: "Two or three distinct words work best, e.g. “Hey Jarvis”.")
      },
      modifier = Modifier.fillMaxWidth(),
    )

    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Voice match", style = MaterialTheme.typography.titleMedium)
        Text(
          model.enrolledPhrase?.let { "Enrolled: “$it” · threshold ${model.threshold?.let(WakeEnrollmentViewModel::fmt)}" }
            ?: "Not enrolled yet. Record ${WakeEnrollmentViewModel.TAKES} takes in a quiet room."
        )
        if (model.mode == WakeEnrollmentViewModel.Mode.ENROLLING) {
          LinearProgressIndicator(
            progress = { model.takes / WakeEnrollmentViewModel.TAKES.toFloat() },
            modifier = Modifier.fillMaxWidth(),
          )
          Text("${model.takes} of ${WakeEnrollmentViewModel.TAKES} takes")
        } else if (model.mode == WakeEnrollmentViewModel.Mode.LOADING) {
          LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (busy) {
            Button(onClick = model::stop) { Text("Stop") }
          } else {
            Button(onClick = { withMic(model::enroll) }) {
              Text(if (model.enrolledPhrase == null) "Record" else "Re-record")
            }
            OutlinedButton(onClick = { withMic(model::test) }, enabled = model.enrolledPhrase != null) {
              Text("Test")
            }
          }
        }
        model.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        for (line in model.testLog) Text(line, style = MaterialTheme.typography.bodySmall)
      }
    }

    HorizontalDivider()

    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Listen for the wake phrase", style = MaterialTheme.typography.titleMedium)
        Text(
          when {
            model.running -> "Listening (see the notification to pause)"
            model.enabled -> "On, but not listening right now"
            else -> "Off"
          },
          style = MaterialTheme.typography.bodySmall,
        )
      }
      Spacer(Modifier.width(8.dp))
      Switch(
        checked = model.enabled,
        enabled = !busy && (model.enrolledPhrase != null || model.enabled),
        onCheckedChange = { on -> if (on) withMic { model.changeEnabled(true) } else model.changeEnabled(false) },
      )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Battery", style = MaterialTheme.typography.titleMedium)
        Text(
          if (model.batteryUnrestricted) "Unrestricted" else
            "Optimised: the phone may stop listening. On Samsung also check Settings → Apps → " +
              "PhoneAgent → Battery → Unrestricted.",
          style = MaterialTheme.typography.bodySmall,
        )
      }
      Spacer(Modifier.width(8.dp))
      OutlinedButton(onClick = onBattery, enabled = !model.batteryUnrestricted) { Text("Set to Unrestricted") }
    }
  }
}
