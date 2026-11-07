package com.google.ai.edge.gallery.phoneagent

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.LlmConversationInstance
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.ToolManager
import com.google.ai.edge.litertlm.ToolProvider
import com.malladi2610.phoneagent.core.agent.AgentEngine
import com.malladi2610.phoneagent.core.agent.AgentError
import com.malladi2610.phoneagent.core.agent.ChatMessage
import com.malladi2610.phoneagent.core.agent.Conversation
import com.malladi2610.phoneagent.core.agent.TurnEvent
import com.malladi2610.phoneagent.core.agent.TurnRequest
import com.malladi2610.phoneagent.core.ollama.OllamaChatApi
import com.malladi2610.phoneagent.core.tool.Surface
import com.malladi2610.phoneagent.core.tool.ToolRegistry
import com.malladi2610.phoneagent.core.tool.ToolUi
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Per-model state for an Ollama Cloud model, kept in `Model.instance` (spec M1 §5.2).
 *
 * @property systemInstruction Gallery's system instruction (Agent Chat's prompt), without the
 *   phone preamble; the preamble is appended fresh on every turn so the local time stays current.
 */
class OllamaConversationInstance(
  val ollamaModel: String,
  var systemInstruction: String,
  var registry: ToolRegistry,
  var conversation: Conversation,
  private val chatApi: () -> OllamaChatApi,
) : LlmConversationInstance {
  internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  @Volatile internal var job: Job? = null

  override fun getTokenCount(): Int = conversation.lastTokenCount

  /**
   * One tool-less request on top of the current history (used by Gallery's summarization
   * compactor, which is disabled for Ollama models; kept for completeness). Doesn't change the
   * history.
   */
  override fun sendMessage(prompt: String, maxOutputTokens: Int?): Message = runBlocking {
    val messages = conversation.messages + ChatMessage(ChatMessage.Role.USER, prompt)
    Message.model(chatApi().chat(ollamaModel, messages, emptyList()).message.content)
  }
}

/** [LlmModelHelper] for `RuntimeType.OLLAMA_CLOUD` (selected in `runtime/ModelHelperExt.kt`). */
object OllamaCloudModelHelper : LlmModelHelper {
  private const val TAG = "PaOllamaHelper"
  private const val GENERIC_ERROR = "Something went wrong talking to Ollama Cloud."

  @Volatile private var appContext: Context? = null
  private val cleanUpListeners = ConcurrentHashMap<String, CleanUpListener>()

  /**
   * Gating (confirm, unlock, permissions) happens inside the phone-tool bridge with its own
   * Gallery UI; [LiteRtToolRegistry] never consults this one. It fails closed in case anything
   * ever does.
   */
  private object NoOpToolUi : ToolUi {
    override suspend fun confirm(summary: String) = false

    override suspend fun requestRuntimePermission(permission: String) = false

    override suspend fun requestUnlock() = false
  }

  private fun engine(): AgentEngine =
    OllamaRuntime.engine(requireNotNull(appContext) { "not initialized" })

  override fun initialize(
    context: Context,
    model: Model,
    taskId: String,
    supportImage: Boolean,
    supportAudio: Boolean,
    onDone: (String) -> Unit,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    initialMessages: List<Message>,
    coroutineScope: CoroutineScope?,
    enableResponseFormat: Boolean,
  ) {
    try {
      appContext = context.applicationContext
      val sys = systemInstruction?.toString().orEmpty()
      model.instance =
        OllamaConversationInstance(
          ollamaModel = OllamaModelSource.ollamaNameOf(model),
          systemInstruction = sys,
          registry = LiteRtToolRegistry(ToolManager(tools)),
          conversation = seed(initialMessages),
          chatApi = { OllamaRuntime.client(context.applicationContext) },
        )
      onDone("")
    } catch (e: Exception) {
      Log.e(TAG, "Initialization failed", e)
      onDone(e.message ?: GENERIC_ERROR)
    }
  }

  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    initialMessages: List<Message>,
    enableResponseFormat: Boolean,
  ) {
    val instance = model.instance as? OllamaConversationInstance ?: return
    instance.job?.cancel()
    instance.job = null
    systemInstruction?.toString()?.let { instance.systemInstruction = it }
    instance.registry = LiteRtToolRegistry(ToolManager(tools))
    instance.conversation = seed(initialMessages)
  }

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    val instance = model.instance as? OllamaConversationInstance
    instance?.scope?.cancel()
    cleanUpListeners.remove(model.name)?.invoke()
    model.instance = null
    onDone()
  }

  override fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit,
    images: List<Bitmap>,
    audioClips: List<ByteArray>,
    coroutineScope: CoroutineScope?,
    extraContext: Map<String, String>,
    responseJsonSchema: String?,
  ) {
    val instance = model.instance as? OllamaConversationInstance
    if (instance == null) {
      onError("Model not initialized.")
      return
    }
    // Images and audio are ignored in M1.
    instance.job?.cancel()
    cleanUpListeners[model.name] = cleanUpListener
    instance.job =
      instance.scope.launch {
        try {
          withSystemMessage(instance)
          val result =
            engine()
              .runTurn(
                TurnRequest(
                  conversation = instance.conversation,
                  userText = input,
                  model = instance.ollamaModel,
                  surface = Surface.CHAT,
                  tools = instance.registry,
                  ui = NoOpToolUi,
                  onEvent = { event ->
                    if (event is TurnEvent.Thinking) resultListener("", false, event.text)
                  },
                )
              )
          cleanUpListeners.remove(model.name)
          resultListener(result.text, true, null)
        } catch (e: CancellationException) {
          // stopResponse() reports the cancellation through the clean-up listener.
        } catch (e: AgentError) {
          cleanUpListeners.remove(model.name)
          onError(e.userMessage)
        } catch (e: Exception) {
          Log.e(TAG, "Ollama turn failed (${e::class.java.simpleName})")
          cleanUpListeners.remove(model.name)
          onError(GENERIC_ERROR)
        }
      }
  }

  override fun stopResponse(model: Model) {
    val instance = model.instance as? OllamaConversationInstance ?: return
    instance.job?.cancel()
    instance.job = null
    cleanUpListeners.remove(model.name)?.invoke()
  }

  /** Gallery's system instruction plus a fresh phone preamble, as the first message. */
  private fun withSystemMessage(instance: OllamaConversationInstance) {
    val text = listOf(instance.systemInstruction.trim(), phonePreamble()).filter { it.isNotEmpty() }
    val system = ChatMessage(ChatMessage.Role.SYSTEM, text.joinToString("\n\n"))
    val messages = instance.conversation.messages
    if (messages.firstOrNull()?.role == ChatMessage.Role.SYSTEM) messages[0] = system
    else messages.add(0, system)
  }

  internal fun phonePreamble(now: ZonedDateTime = ZonedDateTime.now()): String {
    val time = now.format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm", Locale.US))
    return """
      You are PhoneAgent, running on the user's Android phone (${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}).
      Local time: $time (time zone ${now.zone.id}).
      Besides skills and MCP tools, you can call the phone tools directly (for example set_flashlight, set_volume, get_device_status) whenever the user asks for something on the phone. Call several tools in one reply when the request has several parts. Keep the final answer short.
    """
      .trimIndent()
  }

  /** History from Gallery's `initialMessages` (restored sessions); the system message is added per turn. */
  private fun seed(initialMessages: List<Message>): Conversation {
    val conversation = Conversation(systemPrompt = null)
    for (msg in initialMessages) {
      val role =
        when (msg.role) {
          Role.USER -> ChatMessage.Role.USER
          Role.MODEL -> ChatMessage.Role.ASSISTANT
          // Tool results without their calls would be invalid; system is re-added per turn.
          else -> null
        } ?: continue
      val text = msg.contents.toString()
      if (text.isNotBlank()) conversation.messages += ChatMessage(role, text)
    }
    return conversation
  }
}
