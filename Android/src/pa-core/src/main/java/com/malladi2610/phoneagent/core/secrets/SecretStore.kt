package com.malladi2610.phoneagent.core.secrets

enum class SecretKey {
  OLLAMA_API_KEY,
  SARVAM_API_KEY,
}

/** Encrypted at rest (Android Keystore). Values are never logged. */
interface SecretStore {
  fun get(key: SecretKey): String?

  fun put(key: SecretKey, value: String)

  fun clear(key: SecretKey)
}
