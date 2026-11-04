package dev.wearclaude.watch.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface TokenCipher {
    fun encrypt(plain: String): String

    /** Null if the ciphertext cannot be decrypted (for example the key was lost). */
    fun decrypt(cipherText: String): String?
}

class KeystoreTokenCipher : TokenCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    override fun decrypt(cipherText: String): String? = try {
        val all = Base64.decode(cipherText, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, IV_LEN))
        String(c.doFinal(all, IV_LEN, all.size - IV_LEN), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val ALIAS = "wearclaude_token"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}

interface SettingsStore {
    val bridgeUrl: Flow<String?>
    val lastProjectId: Flow<String?>
    suspend fun token(): String?
    suspend fun setPaired(url: String, token: String)
    suspend fun setLastProjectId(id: String)
    suspend fun clear()
}

private val Context.dataStore by preferencesDataStore(name = "wearclaude_settings")

class Settings(
    context: Context,
    private val cipher: TokenCipher = KeystoreTokenCipher(),
) : SettingsStore {
    private val store = context.applicationContext.dataStore
    private val urlKey = stringPreferencesKey("bridgeUrl")
    private val tokenKey = stringPreferencesKey("tokenCipher")
    private val projectKey = stringPreferencesKey("lastProjectId")

    override val bridgeUrl: Flow<String?> = store.data.map { it[urlKey] }
    override val lastProjectId: Flow<String?> = store.data.map { it[projectKey] }

    override suspend fun token(): String? =
        store.data.first()[tokenKey]?.let { cipher.decrypt(it) }

    override suspend fun setPaired(url: String, token: String) {
        val enc = cipher.encrypt(token)
        store.edit { it[urlKey] = url; it[tokenKey] = enc }
    }

    override suspend fun setLastProjectId(id: String) {
        store.edit { it[projectKey] = id }
    }

    override suspend fun clear() {
        store.edit { it.clear() }
    }
}
