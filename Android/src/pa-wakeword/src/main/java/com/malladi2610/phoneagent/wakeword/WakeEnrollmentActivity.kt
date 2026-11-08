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
import android.util.Log
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wake-phrase setup: type any phrase, record [EnrollmentLogic.TAKES] takes (the mic opens at once;
 * models load meanwhile; each take is cut by energy endpointing), check the takes agree, learn the
 * phrase templates + voice profile, test live match costs and voice scores, enable the
 * always-listening service, and lift battery limits.
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

/** Loads a native model on its own thread so the mic can open right away; closes it if discarded. */
private class BackgroundLoad<T : java.io.Closeable>(name: String, load: () -> T) {
  private val lock = Object()
  private var result: Result<T>? = null
  private var discarded = false

  init {
    thread(name = name, isDaemon = true) {
      val r = runCatching(load)
      synchronized(lock) {
        if (discarded) {
          r.getOrNull()?.close()
        } else {
          result = r
        }
        lock.notifyAll()
      }
    }
  }

  /** Waits (cancellably) for the model; rethrows the loading error. */
  suspend fun await(): T {
    while (true) {
      currentCoroutineContext().ensureActive()
      synchronized(lock) {
        result?.let { return it.getOrThrow() }
        lock.wait(50)
      }
    }
  }

  fun isReadyOrFailed(): Boolean = synchronized(lock) { result != null }

  fun close() {
    synchronized(lock) {
      discarded = true
      result?.getOrNull()?.close()
      result = null
    }
  }
}

internal class WakeEnrollmentViewModel(app: Application) : AndroidViewModel(app) {
  enum class Mode {
    IDLE,
    RECORDING,
    ANALYZING,
    TESTING,
  }

  private val settings = PaSettings(app)

  var phrase by mutableStateOf(WakeWord.enrolledPhrase(app) ?: DEFAULT_PHRASE)
  var phraseError by mutableStateOf<String?>(null)
  var mode by mutableStateOf(Mode.IDLE)
  var takes by mutableIntStateOf(0)
  var status by mutableStateOf<String?>(null)
  /** Non-blocking advice about the input level ("too loud", "too quiet"). */
  var hint by mutableStateOf<String?>(null)
  /** Live input level (dBFS) while the mic is open. */
  var levelDb by mutableFloatStateOf(Endpointer.SILENCE_DB)
  var enrolledPhrase by mutableStateOf<String?>(null)
  var matchThreshold by mutableStateOf<Float?>(null)
  var voiceThreshold by mutableStateOf<Float?>(null)
  /** Test mode: match cost of the last utterance (lower = closer to the enrolled phrase). */
  var testCost by mutableStateOf<Float?>(null)
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
    matchThreshold = enrollment?.threshold
    voiceThreshold = enrollment?.profile?.threshold()
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
    val typed = phrase.trim()
    EnrollmentLogic.phraseError(typed)?.let {
      phraseError = it
      return
    }
    job =
      viewModelScope.launch {
        val app = getApplication<Application>()
        WakeWord.stop(app)
        mode = Mode.RECORDING
        takes = 0
        hint = null
        testLog.clear()
        status = "Say “$typed”, then pause (1 of ${EnrollmentLogic.TAKES})."
        val features = BackgroundLoad("pa-wake-features") { TfliteFeatureModels.create(app) }
        val speaker = BackgroundLoad("pa-wake-speaker") { SpeakerEngine.create(app) }
        try {
          withContext(Dispatchers.IO) { MicCapture.open().use { mic -> record(mic, typed, features, speaker) } }
        } catch (e: WakeWordUnavailableException) {
          status = "Wake phrase isn't available on this device: ${e.message}"
        } catch (e: IOException) {
          status = e.message ?: "Microphone unavailable."
        } catch (e: SecurityException) {
          status = "Microphone permission is needed for the wake phrase."
        } finally {
          features.close()
          speaker.close()
          finishCapture()
        }
      }
  }

  /** Runs on the IO dispatcher with the mic open; returns when enrolled, failed or cancelled. */
  private suspend fun record(
    mic: MicCapture,
    typed: String,
    features: BackgroundLoad<TfliteFeatureModels>,
    speaker: BackgroundLoad<SpeakerEngine>,
  ) {
    val recorder = EnrollmentLogic.TakeRecorder()
    val collected = mutableListOf<EnrollmentLogic.TakeAudio>()
    var redos = 0
    var loudUntil = 0L
    var frames = 0L
    while (true) {
      currentCoroutineContext().ensureActive()
      val chunk = mic.read() ?: throw IOException("The microphone stopped delivering audio.")
      if (chunk.isEmpty()) continue
      frames++
      val results = recorder.accept(chunk)
      val level = Endpointer.levelDb(chunk)
      val clippedNow = Endpointer.clippedFraction(chunk) > 0f
      if (clippedNow) loudUntil = frames + LOUD_HINT_FRAMES
      var analyse = false
      withContext(Dispatchers.Main) {
        levelDb = level
        if (frames < loudUntil) hint = LOUD_HINT else if (hint == LOUD_HINT) hint = null
        for (r in results) {
          when (r) {
            EnrollmentLogic.TakeResult.Ignored -> Unit
            is EnrollmentLogic.TakeResult.Rejected -> status = "${r.message} Say “$typed” again."
            is EnrollmentLogic.TakeResult.Accepted -> {
              collected += r.take
              takes = collected.size
              r.hint?.let { hint = it }
              if (collected.size < EnrollmentLogic.TAKES) {
                status = "Got it. Again (${collected.size + 1} of ${EnrollmentLogic.TAKES})."
              } else {
                analyse = true
              }
            }
          }
        }
      }
      if (!analyse) continue

      withContext(Dispatchers.Main) {
        mode = Mode.ANALYZING
        status = "Checking your takes…"
      }
      val models = features.await()
      val outcome =
        EnrollmentLogic.build(typed, collected, models, speaker.await(), allowRedo = redos < EnrollmentLogic.MAX_REDOS)
      when (outcome) {
        is EnrollmentLogic.Outcome.Redo -> {
          redos++
          collected.removeAt(outcome.index)
          recorder.reset() // drop audio that piled up while analysing
          withContext(Dispatchers.Main) {
            takes = collected.size
            mode = Mode.RECORDING
            status = "Take ${outcome.index + 1} didn't sound like the others. Say “$typed” once more."
          }
        }
        is EnrollmentLogic.Outcome.Failed -> {
          withContext(Dispatchers.Main) { status = outcome.message }
          return
        }
        is EnrollmentLogic.Outcome.Done -> {
          WakeWordStore.save(getApplication(), outcome.enrollment)
          val e = outcome.enrollment
          withContext(Dispatchers.Main) {
            status =
              "Enrolled “${e.phrase}” from ${outcome.keptTakes} takes. Match threshold ${fmt3(e.threshold)} " +
                "(takes scored ${outcome.loo.joinToString { fmt3(it) }}); voice threshold ${fmt(e.profile.threshold())}."
            hint = null
          }
          Log.i(TAG, "Enrolled: threshold ${e.threshold}, LOO ${outcome.loo.joinToString()}, voice ${e.profile.threshold()}")
          return
        }
      }
    }
  }

  fun test() {
    if (job?.isActive == true) return
    val enrollment = WakeWordStore.load(getApplication()) ?: return
    val voiceThreshold = enrollment.profile.threshold()
    testLog.clear()
    job =
      viewModelScope.launch {
        val app = getApplication<Application>()
        WakeWord.stop(app)
        mode = Mode.TESTING
        hint = null
        testCost = null
        status = "Say “${enrollment.phrase}”…"
        val features = BackgroundLoad("pa-wake-features") { TfliteFeatureModels.create(app) }
        val speaker = BackgroundLoad("pa-wake-speaker") { SpeakerEngine.create(app) }
        try {
          withContext(Dispatchers.IO) {
            MicCapture.open().use { mic ->
              val ring = RingBuffer((WakePipeline.WINDOW_SECONDS * WakePipeline.SAMPLE_RATE).toInt())
              val waiting = mutableListOf<FloatArray>() // audio heard while the models load
              var detector: QbeDetector? = null
              var seen = 0
              while (true) {
                currentCoroutineContext().ensureActive()
                val chunk = mic.read() ?: throw IOException("The microphone stopped delivering audio.")
                if (chunk.isEmpty()) continue
                ring.write(chunk)
                val level = Endpointer.levelDb(chunk)
                var hit = false
                val d = detector
                if (d != null) {
                  hit = d.accept(chunk)
                } else {
                  waiting += chunk
                  if (features.isReadyOrFailed()) {
                    val created =
                      QbeDetector(SpeechEmbeddingStream(features.await()), PhraseMatcher(enrollment.templates), enrollment.threshold)
                    for (c in waiting) hit = created.accept(c) || hit
                    waiting.clear()
                    detector = created
                  }
                }
                val utterances = detector?.utterances ?: 0
                val cost = detector?.lastCost
                val line =
                  when {
                    hit -> {
                      val embedding = speaker.await().embed(SpeechTrim.trim(ring.snapshot(), WakePipeline.SAMPLE_RATE))
                      ring.clear()
                      if (embedding == null) {
                        "Phrase heard (match ${fmt3(cost!!)}) · too short to check the voice"
                      } else {
                        val score = enrollment.profile.score(embedding)
                        val verdict = if (score >= voiceThreshold) "would wake" else "ignored (voice didn't match)"
                        "Phrase heard (match ${fmt3(cost!!)} ≤ ${fmt3(enrollment.threshold)}) · voice ${fmt(score)} " +
                          "vs ${fmt(voiceThreshold)} → $verdict"
                      }
                    }
                    utterances > seen && cost != null ->
                      if (cost.isFinite()) "Not the phrase (match ${fmt3(cost)} > ${fmt3(enrollment.threshold)})"
                      else "Not the phrase (length too different)"
                    else -> null
                  }
                val newUtterance = utterances > seen
                seen = utterances
                withContext(Dispatchers.Main) {
                  levelDb = level
                  if (newUtterance && cost != null && cost.isFinite()) testCost = cost
                  if (line != null) {
                    testLog.add(0, line)
                    if (testLog.size > 20) testLog.removeAt(testLog.lastIndex)
                  }
                }
              }
            }
          }
        } catch (e: WakeWordUnavailableException) {
          status = "Wake phrase isn't available on this device: ${e.message}"
        } catch (e: IOException) {
          status = e.message ?: "Microphone unavailable."
        } catch (e: SecurityException) {
          status = "Microphone permission is needed for the wake phrase."
        } finally {
          features.close()
          speaker.close()
          finishCapture()
        }
      }
  }

  private fun finishCapture() {
    mode = Mode.IDLE
    levelDb = Endpointer.SILENCE_DB
    testCost = null
    if (settings.wakeWordEnabled) WakeWord.start(getApplication())
    refresh()
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

  companion object {
    private const val TAG = "PaWakeWord"
    const val DEFAULT_PHRASE = "Hey Friday"
    const val LOUD_HINT = "Too loud: move the phone a bit away."
    private const val LOUD_HINT_FRAMES = 20 // 1.6 s

    fun fmt(v: Float) = String.format(Locale.ROOT, "%.2f", v)

    fun fmt3(v: Float) = String.format(Locale.ROOT, "%.3f", v)
  }
}

@Composable
private fun EnrollmentScreen(
  model: WakeEnrollmentViewModel,
  withMic: (() -> Unit) -> Unit,
  onBattery: () -> Unit,
) {
  val busy = model.mode != WakeEnrollmentViewModel.Mode.IDLE
  val micOpen =
    model.mode == WakeEnrollmentViewModel.Mode.RECORDING || model.mode == WakeEnrollmentViewModel.Mode.TESTING
  Column(
    Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Wake phrase", style = MaterialTheme.typography.headlineSmall)
    Text(
      "Say your phrase to start PhoneAgent hands-free, even on the lock screen. PhoneAgent learns " +
        "the phrase from your own recordings, and voice match cuts down false wakes from other " +
        "voices; it is not a security feature.",
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
        Text(model.phraseError ?: "Any phrase. Two or three distinct words work best, e.g. “Hey Friday”.")
      },
      modifier = Modifier.fillMaxWidth(),
    )

    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Your recordings", style = MaterialTheme.typography.titleMedium)
        Text(
          model.enrolledPhrase?.let {
            "Enrolled: “$it” · match ≤ ${model.matchThreshold?.let(WakeEnrollmentViewModel::fmt3)} · " +
              "voice ≥ ${model.voiceThreshold?.let(WakeEnrollmentViewModel::fmt)}"
          }
            ?: "Not enrolled yet. Tap Record and say the phrase ${EnrollmentLogic.TAKES} times, " +
              "pausing after each."
        )
        when (model.mode) {
          WakeEnrollmentViewModel.Mode.RECORDING -> {
            LinearProgressIndicator(
              progress = { model.takes / EnrollmentLogic.TAKES.toFloat() },
              modifier = Modifier.fillMaxWidth(),
            )
            Text("${model.takes} of ${EnrollmentLogic.TAKES} takes")
          }
          WakeEnrollmentViewModel.Mode.ANALYZING -> LinearProgressIndicator(Modifier.fillMaxWidth())
          else -> Unit
        }
        if (micOpen) {
          Text("Mic level", style = MaterialTheme.typography.labelMedium)
          LinearProgressIndicator(
            progress = { ((model.levelDb + 60f) / 60f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color =
              if (model.hint == WakeEnrollmentViewModel.LOUD_HINT) MaterialTheme.colorScheme.error
              else MaterialTheme.colorScheme.primary,
          )
        }
        model.hint?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
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
        if (model.mode == WakeEnrollmentViewModel.Mode.TESTING) {
          val cost = model.testCost
          val thr = model.matchThreshold
          Text(
            if (cost == null || thr == null) "Say the phrase, then pause…"
            else "Last utterance: match ${WakeEnrollmentViewModel.fmt3(cost)} (wakes at ≤ ${WakeEnrollmentViewModel.fmt3(thr)})",
            style = MaterialTheme.typography.bodyMedium,
          )
        }
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
            model.enabled && model.enrolledPhrase == null -> "On, but record your phrase first"
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
