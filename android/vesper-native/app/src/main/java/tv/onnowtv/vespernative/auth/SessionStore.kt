package tv.onnowtv.vespernative.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SessionStore {
    suspend fun read(): String?
    suspend fun save(token: String)
    suspend fun clear()
}

private val Context.sessionData by preferencesDataStore("vesper-native-session")

/** App-private ciphertext only; passwords and provider credentials are never persisted. */
class KeystoreSessionStore(context: Context) : SessionStore {
    private val context = context.applicationContext
    private val tokenKey = stringPreferencesKey("token")
    private val ivKey = stringPreferencesKey("iv")
    private val alias = "vesper-native-session-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    override suspend fun save(token: String) = withContext(Dispatchers.IO) {
        require(token.isNotBlank())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = Base64.encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        context.sessionData.edit { it[tokenKey] = encrypted; it[ivKey] = iv }
        Unit
    }

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        try {
            val data = context.sessionData.data.first()
            val encrypted = data[tokenKey] ?: return@withContext null
            val iv = data[ivKey] ?: error("Missing IV")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { clear(); null }
    }

    override suspend fun clear() { context.sessionData.edit { it.clear() } }
}