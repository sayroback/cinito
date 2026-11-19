package org.cinito.online.search

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class StoredTorznabConfig(
    val id: String,
    val name: String,
    val url: String,
    val apiKey: String,
    val categories: List<String>,
) {
    fun toDomain(): TorznabConfig = TorznabConfig(
        id = id,
        name = name,
        url = url,
        apiKey = apiKey,
        categories = categories.mapNotNull { name ->
            SearchCategory.entries.find { it.name == name }
        }.toSet().ifEmpty { setOf(SearchCategory.Movies, SearchCategory.Series, SearchCategory.Anime) },
    )

    companion object {
        fun fromDomain(config: TorznabConfig): StoredTorznabConfig = StoredTorznabConfig(
            id = config.id,
            name = config.name,
            url = config.url,
            apiKey = config.apiKey,
            categories = config.categories.map { it.name },
        )
    }
}

interface SecretStorage {
    fun saveString(key: String, value: String)
    fun readString(key: String): String?
}

/**
 * Encrypts Torznab API keys and endpoints at rest using an Android Keystore AES-256-GCM key.
 */
class KeystoreSecretStorage(context: Context) : SecretStorage {
    private val prefs = context.getSharedPreferences("cinito_secure_torznab", Context.MODE_PRIVATE)

    override fun saveString(key: String, value: String) {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
        prefs.edit().putString(key, encoded).apply()
    }

    override fun readString(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        return runCatching {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_BYTES) return null
            val iv = combined.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = combined.copyOfRange(GCM_IV_BYTES, combined.size)
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "cinito_torznab_aes_gcm"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}

class TorznabConfigStore(
    private val storage: SecretStorage,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _configs = MutableStateFlow(load())
    val configs: StateFlow<List<TorznabConfig>> = _configs.asStateFlow()

    fun addOrUpdate(name: String, url: String, apiKey: String, categories: Set<SearchCategory>): TorznabConfig? {
        val cleanName = name.trim()
        val cleanUrl = url.trim().trimEnd('/')
        val cleanKey = apiKey.trim()
        if (cleanName.isEmpty() || cleanUrl.isEmpty() || cleanKey.isEmpty()) return null
        val normalizedUrl = if (cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
            cleanUrl
        } else {
            "https://$cleanUrl"
        }
        val config = TorznabConfig(
            id = "torznab_${UUID.randomUUID().toString().take(8)}",
            name = cleanName,
            url = normalizedUrl,
            apiKey = cleanKey,
            categories = categories.ifEmpty { setOf(SearchCategory.Movies, SearchCategory.Series, SearchCategory.Anime) },
        )
        val next = _configs.value.filterNot { it.url == config.url && it.name == config.name } + config
        persist(next)
        return config
    }

    fun remove(id: String) {
        val next = _configs.value.filterNot { it.id == id }
        persist(next)
    }

    private fun load(): List<TorznabConfig> {
        val raw = storage.readString(STORAGE_KEY) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<StoredTorznabConfig>>(raw).map { it.toDomain() }
        }.getOrElse { emptyList() }
    }

    private fun persist(list: List<TorznabConfig>) {
        _configs.value = list
        val payload = json.encodeToString(list.map(StoredTorznabConfig::fromDomain))
        storage.saveString(STORAGE_KEY, payload)
    }

    companion object {
        private const val STORAGE_KEY = "torznab_configs_v1"
    }
}
