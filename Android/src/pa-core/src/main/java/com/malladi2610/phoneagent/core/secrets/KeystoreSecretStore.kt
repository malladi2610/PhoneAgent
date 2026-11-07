package com.malladi2610.phoneagent.core.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey as JcaSecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SecretStore] backed by an AES-256-GCM key that never leaves the Android Keystore. Ciphertext
 * (`base64(iv):base64(ciphertext)`) is kept in the private SharedPreferences file
 * `phoneagent_secrets`.
 *
 * If a value can't be decrypted (key lost after a restore from backup, corrupt entry) it is
 * dropped and [get] returns null, so the user is simply asked for the key again. Secret values are
 * never logged.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
  private val prefs =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  @Synchronized
  override fun get(key: SecretKey): String? {
    val stored = prefs.getString(key.name, null) ?: return null
    return try {
      val (ivB64, ctB64) = stored.split(':', limit = 2).also { require(it.size == 2) }
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(
        Cipher.DECRYPT_MODE,
        getOrCreateKey(),
        GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(ivB64)),
      )
      String(cipher.doFinal(Base64.getDecoder().decode(ctB64)), Charsets.UTF_8)
    } catch (e: Exception) {
      Log.w(TAG, "Dropping unreadable secret ${key.name} (${e::class.java.simpleName})")
      prefs.edit().remove(key.name).apply()
      null
    }
  }

  @Synchronized
  override fun put(key: SecretKey, value: String) {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    val encoded =
      Base64.getEncoder().encodeToString(cipher.iv) + ":" + Base64.getEncoder().encodeToString(ct)
    prefs.edit().putString(key.name, encoded).apply()
  }

  @Synchronized
  override fun clear(key: SecretKey) {
    prefs.edit().remove(key.name).apply()
  }

  private fun getOrCreateKey(): JcaSecretKey {
    val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (ks.getKey(KEY_ALIAS, null) as? JcaSecretKey)?.let {
      return it
    }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(
          KEY_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    )
    return generator.generateKey()
  }

  private companion object {
    const val TAG = "PaSecretStore"
    const val PREFS_NAME = "phoneagent_secrets"
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "phoneagent_secrets_v1"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val TAG_BITS = 128
  }
}
