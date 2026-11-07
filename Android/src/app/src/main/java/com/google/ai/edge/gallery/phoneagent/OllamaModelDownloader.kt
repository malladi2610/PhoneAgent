package com.google.ai.edge.gallery.phoneagent

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.ModelDownloader
import com.google.ai.edge.gallery.data.Task

/**
 * [ModelDownloader] for `RuntimeType.OLLAMA_CLOUD`. Cloud models have nothing to download, so
 * they always report as downloaded; a missing or bad API key surfaces when the chat runs. Mirrors
 * the contract of `AICoreModelDownloader` (auto-"downloaded" on startup, no cancel, no delete).
 */
class OllamaModelDownloader : ModelDownloader {
  private val ready = ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED)

  override suspend fun queryStatus(model: Model): ModelDownloadStatus = ready

  override fun download(
    task: Task?,
    model: Model,
    includeExtraDataFiles: Boolean,
    onStatusUpdated: (Model, ModelDownloadStatus) -> Unit,
  ) {
    onStatusUpdated(model, ready)
  }

  override val supportsCancel: Boolean
    get() = false

  override fun cancel(model: Model) {}

  override suspend fun delete(model: Model) {}
}
