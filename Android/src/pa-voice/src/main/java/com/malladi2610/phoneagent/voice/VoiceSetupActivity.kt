package com.malladi2610.phoneagent.voice

import android.Manifest
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.malladi2610.phoneagent.core.agent.AgentError
import com.malladi2610.phoneagent.core.voice.VoiceTrigger
import com.malladi2610.phoneagent.voice.sarvam.SarvamWire
import kotlinx.coroutines.launch

/** "Voice & assistant" setup: mic permission, default-assistant role, languages, voice test. */
class VoiceSetupActivity : ComponentActivity() {
  private var resumeTick by mutableIntStateOf(0)

  private val permissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resumeTick++ }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    PaVoice.install(applicationContext)
    enableEdgeToEdge()
    setContent {
      val dark = isSystemInDarkTheme()
      MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) { Screen(resumeTick) }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    resumeTick++
  }

  private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  private fun isAssistant(): Boolean {
    val rm = getSystemService(RoleManager::class.java) ?: return false
    return rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
  }

  /**
   * The Assistant role is not requestable (roles.xml: requestable="false"), so open the screen
   * where the user picks it: "Assist & voice input" / "Digital assistant app" first, then the
   * default-apps list.
   */
  private fun openAssistantSettings() {
    val candidates =
      listOf(
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
      )
    for (intent in candidates) {
      try {
        startActivity(intent)
        return
      } catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
    }
  }

  @Composable
  private fun Screen(tick: Int) {
    val settings = remember { PaVoice.settings() }
    val scope = rememberCoroutineScope()
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    // Re-read on resume (returning from Settings).
    val micGranted = remember(tick) { hasMic() }
    val assistant = remember(tick) { isAssistant() }

    Column(
      Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("Voice & assistant", style = MaterialTheme.typography.headlineSmall)

      Section("Microphone") {
        Text(if (micGranted) "Microphone access: allowed" else "PhoneAgent needs the microphone to hear you.")
        if (!micGranted) {
          Button(
            onClick = {
              permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            }
          ) {
            Text("Allow microphone")
          }
        }
      }

      Section("Digital assistant") {
        Text(
          if (assistant) "PhoneAgent is your digital assistant."
          else "Make PhoneAgent your digital assistant so the side button and lock screen open it."
        )
        if (!assistant) {
          Button(onClick = { openAssistantSettings() }) { Text("Make PhoneAgent your digital assistant") }
          Text(
            "On Samsung: Settings → Apps → Choose default apps → Digital assistant app → " +
              "Device assistance app → PhoneAgent.",
            style = MaterialTheme.typography.bodySmall,
          )
        }
        Text("Side button (Samsung)", style = MaterialTheme.typography.titleSmall)
        Text(
          "Settings → Advanced features → Side button → Press and hold → Digital assistant. " +
            "Then press and hold the side button to talk to PhoneAgent, even on the lock screen.",
          style = MaterialTheme.typography.bodySmall,
        )
      }

      Section("Language and voice") {
        Picker("I speak", STT_LANGUAGES, settings.sttLanguage) { settings.sttLanguage = it }
        Picker("Reply language (when it can't match yours)", SarvamWire.TTS_LANGUAGES.sorted(), settings.ttsLanguage) {
          settings.ttsLanguage = it
        }
        Picker("Voice", SPEAKERS, settings.ttsSpeaker) { settings.ttsSpeaker = it }
      }

      Section("Try it") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            enabled = !testing,
            onClick = {
              testing = true
              testMessage = null
              scope.launch {
                testMessage =
                  try {
                    PaVoice.speechOutput.speak("Hi, I'm PhoneAgent. This is my voice.", settings.ttsLanguage)
                    "Voice works."
                  } catch (e: AgentError) {
                    e.userMessage
                  } catch (e: Exception) {
                    "Couldn't play the test voice."
                  }
                testing = false
              }
            },
          ) {
            Text("Test voice")
          }
          Button(
            enabled = micGranted,
            onClick = { PaVoice.launcher.startVoiceTurn(VoiceTrigger.IN_APP_MIC) },
          ) {
            Text("Talk now")
          }
        }
        testMessage?.let { Text(it) }
      }
    }
  }

  @Composable
  private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
      }
    }
  }

  @Composable
  private fun Picker(label: String, options: List<String>, initial: String, onSelect: (String) -> Unit) {
    var selected by remember { mutableStateOf(initial) }
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
      Box {
        TextButton(onClick = { open = true }) { Text(selected) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
          for (o in options) {
            DropdownMenuItem(
              text = { Text(o) },
              onClick = {
                selected = o
                onSelect(o)
                open = false
              },
            )
          }
        }
      }
    }
  }

  private companion object {
    val STT_LANGUAGES =
      listOf("auto", "en-IN", "hi-IN", "te-IN", "ta-IN", "kn-IN", "ml-IN", "mr-IN", "bn-IN", "gu-IN", "pa-IN", "or-IN")
    val SPEAKERS =
      listOf("ishita", "ratan", "priya", "suhani", "shubh", "ashutosh", "ritu", "kavya", "simran", "aditya", "rahul", "kabir")
  }
}
