package com.google.ai.edge.gallery.phoneagent.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.phoneagent.OllamaRuntime
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import com.malladi2610.phoneagent.core.PhoneAgentServices
import com.malladi2610.phoneagent.core.agent.AgentError
import com.malladi2610.phoneagent.core.secrets.SecretKey
import com.malladi2610.phoneagent.core.settings.PaSettings
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * "PhoneAgent setup" (its own launcher icon): Ollama and Sarvam API keys, the voice model, and
 * links to the other modules' setup screens. Keys are kept only in the SecretStore and are never
 * shown again in full, logged, or saved in instance state.
 */
class PhoneAgentSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent { GalleryTheme { SettingsScreen() } }
  }

  companion object {
    const val MAIN_ACTIVITY = "com.google.ai.edge.gallery.MainActivity"
    const val PERMISSIONS_ACTIVITY = "com.malladi2610.phoneagent.tools.PermissionsActivity"
    const val VOICE_SETUP_ACTIVITY = "com.malladi2610.phoneagent.voice.VoiceSetupActivity"
    const val WAKE_ENROLLMENT_ACTIVITY = "com.malladi2610.phoneagent.wakeword.WakeEnrollmentActivity"
  }
}

private fun settings(context: Context): PaSettings =
  if (PhoneAgentServices.isInstalled()) PhoneAgentServices.get().settings
  else PaSettings(context.applicationContext)

/** "Saved key ending in …abcd", or null when no key is stored. */
private fun savedKeyHint(context: Context, key: SecretKey): String? =
  OllamaRuntime.secrets(context).get(key)?.takeIf { it.isNotBlank() }?.let {
    "Saved key ending in …${it.takeLast(4)}"
  }

/** Opens another module's screen by class name; false if that module isn't in this build. */
private fun openByClassName(context: Context, className: String): Boolean {
  val intent = Intent().setClassName(context, className)
  if (intent.resolveActivity(context.packageManager) == null) return false
  return runCatching { context.startActivity(intent) }.isSuccess
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
  Scaffold(topBar = { TopAppBar(title = { Text("PhoneAgent setup") }) }) { padding ->
    Column(
      modifier =
        Modifier.padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      OllamaSection()
      HorizontalDivider()
      SarvamSection()
      HorizontalDivider()
      OtherSetupSection()
    }
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SecretField(label: String, value: String, onValueChange: (String) -> Unit) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label) },
    singleLine = true,
    visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun OllamaSection() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val secrets = remember { OllamaRuntime.secrets(context) }
  var keyInput by remember { mutableStateOf("") }
  var savedHint by remember { mutableStateOf(savedKeyHint(context, SecretKey.OLLAMA_API_KEY)) }
  var status by remember { mutableStateOf("") }
  var needsReload by remember { mutableStateOf(false) }
  var models by remember { mutableStateOf<List<String>>(emptyList()) }
  var voiceModel by remember { mutableStateOf(settings(context).voiceModel) }
  var menuOpen by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }

  /** Lists models with the typed key, or the saved one when the field is empty. */
  fun listModels(onResult: (List<String>) -> Unit) {
    val typed = keyInput.trim()
    val client =
      if (typed.isNotEmpty()) OllamaRuntime.clientForKey(typed) else OllamaRuntime.client(context)
    busy = true
    scope.launch {
      try {
        client.verifyKey() // /api/tags alone accepts any key; this one authenticates.
        val names = client.listModels()
        models = names
        onResult(names)
      } catch (e: CancellationException) {
        throw e
      } catch (e: AgentError) {
        status = e.userMessage
      } catch (e: Exception) {
        status = "Couldn't reach Ollama Cloud."
      } finally {
        busy = false
      }
    }
  }

  SectionTitle("Ollama Cloud")
  Text(savedHint ?: "No Ollama API key saved.", style = MaterialTheme.typography.bodyMedium)
  SecretField("Ollama API key", keyInput) { keyInput = it }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(
      enabled = keyInput.isNotBlank(),
      onClick = {
        secrets.put(SecretKey.OLLAMA_API_KEY, keyInput.trim())
        keyInput = ""
        savedHint = savedKeyHint(context, SecretKey.OLLAMA_API_KEY)
        status = "Key saved."
        needsReload = true
      },
    ) {
      Text("Save")
    }
    OutlinedButton(enabled = !busy, onClick = {
      status = "Testing…"
      listModels { status = "Key works: ${it.size} models with tool support." }
    }) {
      Text("Test")
    }
    TextButton(
      enabled = savedHint != null,
      onClick = {
        secrets.clear(SecretKey.OLLAMA_API_KEY)
        savedHint = null
        status = "Key removed."
        needsReload = true
      },
    ) {
      Text("Remove")
    }
  }
  if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
  if (needsReload) {
    // ModelManagerViewModel loads the model list when the main screen is created, so restart it.
    OutlinedButton(
      onClick = {
        needsReload = false
        val intent =
          Intent()
            .setClassName(context, PhoneAgentSettingsActivity.MAIN_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
      }
    ) {
      Text("Reload models in PhoneAgent")
    }
  }

  SectionTitle("Voice model")
  Text(
    "Used for spoken commands: ${voiceModel ?: "not chosen yet"}",
    style = MaterialTheme.typography.bodyMedium,
  )
  Box {
    OutlinedButton(
      enabled = !busy,
      onClick = {
        if (models.isNotEmpty()) menuOpen = true
        else listModels { if (it.isNotEmpty()) menuOpen = true else status = "No models found." }
      },
    ) {
      Text("Choose voice model")
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
      for (name in models) {
        DropdownMenuItem(
          text = { Text(name) },
          onClick = {
            settings(context).voiceModel = name
            voiceModel = name
            menuOpen = false
          },
        )
      }
    }
  }
}

@Composable
private fun SarvamSection() {
  val context = LocalContext.current
  val secrets = remember { OllamaRuntime.secrets(context) }
  var keyInput by remember { mutableStateOf("") }
  var savedHint by remember { mutableStateOf(savedKeyHint(context, SecretKey.SARVAM_API_KEY)) }

  SectionTitle("Sarvam (voice)")
  Text(savedHint ?: "No Sarvam API key saved.", style = MaterialTheme.typography.bodyMedium)
  SecretField("Sarvam API key", keyInput) { keyInput = it }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(
      enabled = keyInput.isNotBlank(),
      onClick = {
        secrets.put(SecretKey.SARVAM_API_KEY, keyInput.trim())
        keyInput = ""
        savedHint = savedKeyHint(context, SecretKey.SARVAM_API_KEY)
      },
    ) {
      Text("Save")
    }
    TextButton(
      enabled = savedHint != null,
      onClick = {
        secrets.clear(SecretKey.SARVAM_API_KEY)
        savedHint = null
      },
    ) {
      Text("Remove")
    }
  }
}

@Composable
private fun OtherSetupSection() {
  val context = LocalContext.current
  var message by remember { mutableStateOf("") }

  @Composable
  fun Link(label: String, className: String) {
    OutlinedButton(
      onClick = { message = if (openByClassName(context, className)) "" else "$label: not installed" },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(label)
    }
  }

  SectionTitle("More setup")
  Link("Phone permissions", PhoneAgentSettingsActivity.PERMISSIONS_ACTIVITY)
  Link("Voice & assistant", PhoneAgentSettingsActivity.VOICE_SETUP_ACTIVITY)
  Link("Wake phrase", PhoneAgentSettingsActivity.WAKE_ENROLLMENT_ACTIVITY)
  if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
  Text(" ", modifier = Modifier.padding(bottom = 24.dp))
}
