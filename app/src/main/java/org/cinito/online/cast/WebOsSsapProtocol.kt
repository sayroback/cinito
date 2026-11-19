package org.cinito.online.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class SsapMessageType {
    Response,
    Registered,
    Error,
    Unknown,
}

data class SsapEnvelope(
    val type: SsapMessageType,
    val id: String? = null,
    val error: String? = null,
    val payload: JsonObject? = null,
) {
    val returnValue: Boolean?
        get() = payload?.get("returnValue")?.jsonPrimitive?.booleanOrNull

    val isPairingPrompt: Boolean
        get() = payload?.get("pairingType")?.jsonPrimitive?.content == "PROMPT"

    val clientKey: String?
        get() = payload?.get("client-key")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
}

/**
 * Pure JSON encoder and envelope parser for LG webOS Second Screen Access Protocol (SSAP).
 * Works across stock retail LG webOS TVs (ports 3000/3001) without requiring Developer Mode.
 */
object WebOsSsapProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    private val PERMISSIONS = listOf(
        "LAUNCH",
        "LAUNCH_WEBAPP",
        "APP_TO_APP",
        "CONTROL_AUDIO",
        "CONTROL_DISPLAY",
        "CONTROL_INPUT_MEDIA_PLAYBACK",
        "CONTROL_INPUT_TEXT",
        "CONTROL_POWER",
        "READ_INSTALLED_APPS",
        "READ_CURRENT_CHANNEL",
        "WRITE_NOTIFICATION_TOAST",
    )

    fun buildRegisterPayload(clientKey: String?, requestId: String = "reg_0"): String {
        val manifest = buildJsonObject {
            put("manifestVersion", JsonPrimitive(1))
            put("appId", JsonPrimitive("org.cinito.online"))
            put("vendorId", JsonPrimitive("org.cinito"))
            put("localizedAppNames", buildJsonObject {
                put("", JsonPrimitive("CiniTo"))
                put("es-MX", JsonPrimitive("CiniTo"))
            })
            put("permissions", buildJsonArray {
                PERMISSIONS.forEach { add(JsonPrimitive(it)) }
            })
        }

        val payload = buildJsonObject {
            put("forcePairing", JsonPrimitive(false))
            put("pairingType", JsonPrimitive("PROMPT"))
            if (!clientKey.isNullOrBlank()) {
                put("client-key", JsonPrimitive(clientKey))
            }
            put("manifest", manifest)
        }

        return buildJsonObject {
            put("id", JsonPrimitive(requestId))
            put("type", JsonPrimitive("register"))
            put("payload", payload)
        }.toString()
    }

    fun buildRequestPayload(
        id: String,
        uri: String,
        payload: JsonObject? = null,
    ): String = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("type", JsonPrimitive("request"))
        put("uri", JsonPrimitive(uri))
        if (payload != null) {
            put("payload", payload)
        }
    }.toString()

    fun parseEnvelope(raw: String): SsapEnvelope {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return SsapEnvelope(type = SsapMessageType.Unknown)
        val typeStr = obj["type"]?.jsonPrimitive?.content.orEmpty()
        val type = when (typeStr) {
            "response" -> SsapMessageType.Response
            "registered" -> SsapMessageType.Registered
            "error" -> SsapMessageType.Error
            else -> SsapMessageType.Unknown
        }
        val id = obj["id"]?.jsonPrimitive?.content
        val error = obj["error"]?.jsonPrimitive?.content
        val payload = obj["payload"] as? JsonObject
        return SsapEnvelope(type = type, id = id, error = error, payload = payload)
    }

    fun extractLaunchPointIds(envelope: SsapEnvelope): Set<String> {
        val arr = envelope.payload?.get("launchPoints") as? JsonArray ?: return emptySet()
        return arr.mapNotNull { elem ->
            (elem as? JsonObject)?.get("id")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        }.toSet()
    }

    fun extractPointerSocketPath(envelope: SsapEnvelope): String? =
        envelope.payload?.get("socketPath")?.jsonPrimitive?.content?.takeIf { it.startsWith("ws") }

    fun buildPointerButtonFrame(key: WebOsRemoteKey): String =
        "type:button\nname:${key.buttonName}\n\n"
}
