package com.oracleairescue

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

class SecureStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("oracle_ai_rescue_secure", Context.MODE_PRIVATE)
    private val keyAlias = "oracle_ai_rescue_aes_key_v1"

    fun saveModelSettings(settings: ModelSettings) {
        put("model.provider", settings.provider)
        put("model.apiKey", settings.apiKey)
        put("model.baseUrl", settings.baseUrl)
        put("model.modelName", settings.modelName)
        put("model.temperature", settings.temperature.toString())
        put("model.maxContextCharacters", settings.maxContextCharacters.toString())
    }

    fun loadModelSettings(): ModelSettings = ModelSettings(
        provider = get("model.provider", "gemini"),
        apiKey = get("model.apiKey", ""),
        baseUrl = get("model.baseUrl", "https://generativelanguage.googleapis.com/v1beta/openai"),
        modelName = get("model.modelName", "gemini-2.5-flash"),
        temperature = get("model.temperature", "0.2").toDoubleOrNull() ?: 0.2,
        maxContextCharacters = get("model.maxContextCharacters", "60000").toIntOrNull()?.coerceIn(8_000, 200_000) ?: 60_000
    )

    fun saveServerSettings(settings: ServerSettings) {
        put("server.name", settings.name)
        put("server.host", settings.host)
        put("server.port", settings.port.toString())
        put("server.username", settings.username)
        put("server.password", settings.password)
        put("server.privateKey", settings.privateKey)
        put("server.privateKeyPassphrase", settings.privateKeyPassphrase)
        put("server.projectPath", settings.projectPath)
        put("server.serviceName", settings.serviceName)
        put("server.dockerContainer", settings.dockerContainer)
        put("server.strictHostKeyChecking", settings.strictHostKeyChecking.toString())
        put("server.allowSudoCommands", settings.allowSudoCommands.toString())
    }

    fun loadServerSettings(): ServerSettings = ServerSettings(
        name = get("server.name", "我的 Oracle AI 助理"),
        host = get("server.host", ""),
        port = get("server.port", "22").toIntOrNull() ?: 22,
        username = get("server.username", "ubuntu"),
        password = get("server.password", ""),
        privateKey = get("server.privateKey", ""),
        privateKeyPassphrase = get("server.privateKeyPassphrase", ""),
        projectPath = get("server.projectPath", ""),
        serviceName = get("server.serviceName", ""),
        dockerContainer = get("server.dockerContainer", ""),
        strictHostKeyChecking = get("server.strictHostKeyChecking", "false").toBooleanStrictOrNull() ?: false,
        allowSudoCommands = get("server.allowSudoCommands", "false").toBooleanStrictOrNull() ?: false
    )


    fun saveModelCatalog(provider: String, models: List<ModelOption>) {
        put("models.catalog.$provider", encodeModels(models.take(500)))
    }

    fun loadModelCatalog(provider: String): List<ModelOption> = decodeModels(get("models.catalog.$provider", ""))

    fun saveFavoriteModels(provider: String, models: List<ModelOption>) {
        put("models.favorites.$provider", encodeModels(models.distinctBy { it.id }.take(120)))
    }

    fun loadFavoriteModels(provider: String): List<ModelOption> = decodeModels(get("models.favorites.$provider", ""))

    fun saveChatMessages(messages: List<ChatMessage>) {
        val arr = JSONArray()
        messages
            .filter { it.role in setOf("system", "user", "assistant") && it.content.isNotBlank() }
            .takeLast(80)
            .forEach { msg ->
                arr.put(JSONObject().put("role", msg.role).put("content", msg.content.take(60_000)))
            }
        put("chat.messages", arr.toString().take(240_000))
    }

    fun loadChatMessages(): List<ChatMessage> {
        val raw = get("chat.messages", "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val role = item.optString("role")
                    val content = item.optString("content")
                    if (role in setOf("system", "user", "assistant") && content.isNotBlank()) {
                        add(ChatMessage(role, content))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun clearChatMessages() {
        put("chat.messages", "")
    }

    private fun encodeModels(models: List<ModelOption>): String {
        val arr = JSONArray()
        models.forEach { model ->
            arr.put(
                JSONObject()
                    .put("id", model.id)
                    .put("displayName", model.displayName)
                    .put("description", model.description)
            )
        }
        return arr.toString()
    }

    private fun decodeModels(raw: String): List<ModelOption> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    add(
                        ModelOption(
                            id = id,
                            displayName = item.optString("displayName").ifBlank { id },
                            description = item.optString("description")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun appendHistory(entry: RepairHistory) {
        val old = get("history.items", "")
        val item = listOf(entry.timestamp, entry.title, entry.content)
            .joinToString("\u001F") { it.replace("\u001E", " ").replace("\u001F", " ") }
        val newValue = (item + "\u001E" + old).take(120_000)
        put("history.items", newValue)
    }

    fun loadHistory(): List<RepairHistory> {
        val raw = get("history.items", "")
        if (raw.isBlank()) return emptyList()
        return raw.split("\u001E")
            .filter { it.isNotBlank() }
            .mapNotNull { row ->
                val parts = row.split("\u001F")
                if (parts.size >= 3) RepairHistory(parts[0], parts[1], parts.subList(2, parts.size).joinToString("\u001F")) else null
            }
    }

    fun clearHistory() {
        put("history.items", "")
    }

    private fun put(key: String, value: String) {
        val encrypted = encrypt(value)
        prefs.edit().putString(key, encrypted).apply()
    }

    private fun get(key: String, default: String): String {
        val encrypted = prefs.getString(key, null) ?: return default
        return runCatching { decrypt(encrypted) }.getOrDefault(default)
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val data = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        return "$iv:$data"
    }

    private fun decrypt(value: String): String {
        val parts = value.split(":", limit = 2)
        if (parts.size != 2) return ""
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val data = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(data), StandardCharsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
