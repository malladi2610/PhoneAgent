package com.google.ai.edge.gallery.phoneagent

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.BackendSpec
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.LlmProfile
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadInfo
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.malladi2610.phoneagent.core.PhoneAgentServices
import com.malladi2610.phoneagent.core.agent.AgentEngine
import com.malladi2610.phoneagent.core.agent.AgentError
import com.malladi2610.phoneagent.core.ollama.OllamaAgentEngine
import com.malladi2610.phoneagent.core.ollama.OllamaClient
import com.malladi2610.phoneagent.core.secrets.KeystoreSecretStore
import com.malladi2610.phoneagent.core.secrets.SecretKey
import com.malladi2610.phoneagent.core.secrets.SecretStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/** Whether [this] model is served by Ollama Cloud (used at the upstream `isAiCore` touch points). */
val Model.isOllamaCloud: Boolean
  get() = backendSpec.runtimeType == RuntimeType.OLLAMA_CLOUD

/**
 * Registers the Ollama Cloud models (from `GET /api/tags`) under the Agent Chat task. Called once
 * per allowlist load from `ModelManagerViewModel.loadModelAllowlist` (touch point, UPSTREAM.md).
 * Nothing is added to the allowlist JSON and nothing is downloaded.
 */
object OllamaModelSource {
  private const val TAG = "PaOllamaModels"
  const val NAME_PREFIX = "Ollama Cloud · "
  private const val CACHE_PREFS = "phoneagent_ollama_models"
  private const val CACHE_KEY = "names"
  private const val LIST_TIMEOUT_MS = 8_000L

  /** Cloud models have large contexts; this only feeds Gallery's token-limit UI. */
  private const val MAX_TOKENS = 128_000

  /** The Ollama model name (exactly as returned by `/api/tags`) behind a Gallery [Model]. */
  fun ollamaNameOf(model: Model): String = model.name.removePrefix(NAME_PREFIX)

  fun createModel(ollamaName: String): Model =
    Model(
      name = NAME_PREFIX + ollamaName,
      displayName = NAME_PREFIX + ollamaName,
      info = "Runs on Ollama Cloud (https://ollama.com). Needs an internet connection and an Ollama API key.",
      learnMoreUrl = "https://ollama.com/search?c=cloud",
      downloadInfo = ModelDownloadInfo(url = "", sizeInBytes = 0L),
      backendSpec = BackendSpec(runtimeType = RuntimeType.OLLAMA_CLOUD),
      llmProfile = LlmProfile(maxTokens = MAX_TOKENS),
      showRunAgainButton = false,
    )

  /**
   * Adds one [Model] per Ollama Cloud model to the Agent Chat task in [tasks], replacing the ones a
   * previous load added. With no key, nothing is added. If the list can't be fetched (offline,
   * timeout) the last successfully fetched list is used, so the chat itself can show the error.
   * Never throws; errors are logged without the key.
   */
  suspend fun addTo(tasks: List<Task>, context: Context) {
    val task = tasks.find { it.id == BuiltInTaskId.LLM_AGENT_CHAT } ?: return
    try {
      task.models.removeAll { it.isOllamaCloud }
      val names = fetchOrCachedNames(context)
      for (name in names) {
        task.models.add(createModel(name))
      }
      Log.d(TAG, "Registered ${names.size} Ollama Cloud models")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't register Ollama Cloud models (${e::class.java.simpleName})")
    }
  }

  private suspend fun fetchOrCachedNames(context: Context): List<String> {
    if (OllamaRuntime.secrets(context).get(SecretKey.OLLAMA_API_KEY).isNullOrBlank()) {
      Log.d(TAG, "No Ollama API key; no cloud models")
      return emptyList()
    }
    val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
    val fetched =
      try {
        withTimeoutOrNull(LIST_TIMEOUT_MS) { OllamaRuntime.client(context).listModels() }
      } catch (e: AgentError) {
        Log.w(TAG, "Listing Ollama models failed: ${e::class.java.simpleName}")
        if (e is AgentError.Unauthorized) return emptyList()
        null
      }
    if (fetched != null) {
      prefs.edit().putString(CACHE_KEY, fetched.joinToString("\n")).apply()
      return fetched
    }
    return prefs.getString(CACHE_KEY, null)?.split("\n")?.filter { it.isNotBlank() }.orEmpty()
  }
}

/**
 * Where the Ollama runtime gets its services. Uses [PhoneAgentServices] once the app installs it
 * (`PhoneAgentGraph`), and otherwise falls back to its own instances so the runtime also works
 * before integration.
 */
internal object OllamaRuntime {
  private val http by lazy { OkHttpClient() }
  @Volatile private var fallbackSecrets: SecretStore? = null
  @Volatile private var fallbackClient: OllamaClient? = null
  @Volatile private var fallbackEngine: AgentEngine? = null

  fun secrets(context: Context): SecretStore =
    if (PhoneAgentServices.isInstalled()) PhoneAgentServices.get().secrets
    else
      fallbackSecrets
        ?: synchronized(this) {
          fallbackSecrets ?: KeystoreSecretStore(context.applicationContext).also { fallbackSecrets = it }
        }

  /** A client that reads the stored key on every call. */
  fun client(context: Context): OllamaClient =
    fallbackClient
      ?: synchronized(this) {
        fallbackClient
          ?: OllamaClient(http, { secrets(context).get(SecretKey.OLLAMA_API_KEY) }).also {
            fallbackClient = it
          }
      }

  /** A client for a key that hasn't been saved yet (settings "Test" button). */
  fun clientForKey(key: String): OllamaClient = OllamaClient(http, { key })

  fun engine(context: Context): AgentEngine =
    if (PhoneAgentServices.isInstalled()) PhoneAgentServices.get().agentEngine
    else
      fallbackEngine
        ?: synchronized(this) {
          fallbackEngine ?: OllamaAgentEngine(client(context)).also { fallbackEngine = it }
        }
}
