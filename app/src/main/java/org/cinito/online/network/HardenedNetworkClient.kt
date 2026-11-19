package org.cinito.online.network

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

class PublicCleartextRejectedException(url: String) :
    IOException("Blocked cleartext HTTP request to public host: ${NetworkSafety.redactUrl(url)}")

class MobileDataBlockedException(url: String) :
    IOException("Uso de datos móviles bloqueado (Solo Wi-Fi activo): ${NetworkSafety.redactUrl(url)}")

class UpstreamBlockedException(url: String, status: Int) :
    IOException("Upstream returned HTTP $status for ${NetworkSafety.redactUrl(url)}")

object NetworkSafety {
    private val SENSITIVE_PARAM = Regex("""(?i)([?&](?:apikey|api_key|token|pass|password)=)[^&]+""")

    fun redactUrl(url: String): String = SENSITIVE_PARAM.replace(url, "$1[REDACTED]")

    fun isCleartextPermittedHost(host: String): Boolean {
        val h = host.trim().lowercase().trim('[', ']').substringBefore('%')
        if (h == "localhost" || h == "127.0.0.1" || h == "::1") {
            return true
        }
        val ipv4 = parseIpv4(h)
        if (ipv4 != null && ipv4[0] == 127) {
            return true
        }
        val ipv6 = parseIpv6Bytes(h)
        if (ipv6 != null && (isLoopbackIpv6(ipv6) || isIpv4MappedLoopback(ipv6))) {
            return true
        }
        return isPrivateLanHost(h)
    }

    /**
     * Validates non-loopback LAN hosts (RFC 1918, IPv4 link-local 169.254/16,
     * IPv6 link-local fe80::/10 and ULA fc00::/7, mDNS .local).
     * Rejects localhost / 127.0.0.0/8 / ::1 so untrusted SSDP/UPnP peers cannot target
     * local phone loopback services (SSRF-to-loopback prevention).
     */
    fun isPrivateLanHost(host: String): Boolean {
        val h = host.trim().lowercase().trim('[', ']').substringBefore('%')
        if (h.isEmpty() || h == "localhost" || h == "::1") {
            return false
        }
        if (h.endsWith(".local") && h.length > ".local".length) {
            return true
        }
        val ipv4 = parseIpv4(h)
        if (ipv4 != null) {
            return isPrivateOrLinkLocalIpv4(ipv4)
        }
        val ipv6 = parseIpv6Bytes(h) ?: return false
        if (isLoopbackIpv6(ipv6) || isUnspecifiedIpv6(ipv6)) {
            return false
        }
        val mappedIpv4 = extractIpv4Mapped(ipv6)
        if (mappedIpv4 != null) {
            return isPrivateOrLinkLocalIpv4(mappedIpv4)
        }
        val b0 = ipv6[0].toInt() and 0xFF
        val b1 = ipv6[1].toInt() and 0xFF
        // fe80::/10 (link-local) or fc00::/7 (ULA)
        val isLinkLocal = b0 == 0xFE && (b1 and 0xC0) == 0x80
        val isUniqueLocal = (b0 and 0xFE) == 0xFC
        return isLinkLocal || isUniqueLocal
    }

    private fun isPrivateOrLinkLocalIpv4(ipv4: IntArray): Boolean {
        val b0 = ipv4[0]
        val b1 = ipv4[1]
        return b0 == 10 ||
            (b0 == 192 && b1 == 168) ||
            (b0 == 172 && b1 in 16..31) ||
            (b0 == 169 && b1 == 254)
    }

    private fun isLoopbackIpv6(bytes: ByteArray): Boolean {
        for (i in 0 until 15) {
            if (bytes[i].toInt() != 0) return false
        }
        return bytes[15].toInt() == 1
    }

    private fun isUnspecifiedIpv6(bytes: ByteArray): Boolean = bytes.all { it.toInt() == 0 }

    private fun extractIpv4Mapped(bytes: ByteArray): IntArray? {
        for (i in 0 until 10) {
            if (bytes[i].toInt() != 0) return null
        }
        if ((bytes[10].toInt() and 0xFF) != 0xFF || (bytes[11].toInt() and 0xFF) != 0xFF) {
            return null
        }
        return intArrayOf(
            bytes[12].toInt() and 0xFF,
            bytes[13].toInt() and 0xFF,
            bytes[14].toInt() and 0xFF,
            bytes[15].toInt() and 0xFF,
        )
    }

    private fun isIpv4MappedLoopback(bytes: ByteArray): Boolean {
        val mapped = extractIpv4Mapped(bytes) ?: return false
        return mapped[0] == 127
    }

    /** Parses a numeric IPv6 literal into 16 bytes without triggering any DNS lookup. */
    private fun parseIpv6Bytes(host: String): ByteArray? {
        if (!host.contains(':') || host.any { it.isWhitespace() }) return null
        val doubleColonIdx = host.indexOf("::")
        if (doubleColonIdx != host.lastIndexOf("::")) return null // more than one ::

        val (leftRaw, rightRaw) = if (doubleColonIdx >= 0) {
            host.substring(0, doubleColonIdx) to host.substring(doubleColonIdx + 2)
        } else {
            host to null
        }

        val leftParts = if (leftRaw.isEmpty()) emptyList() else leftRaw.split(':')
        val rightParts = if (rightRaw.isNullOrEmpty()) emptyList() else rightRaw.split(':')

        val embeddedIpv4: IntArray?
        val rightHextets: List<String>
        val leftHextets: List<String>

        val lastToken = if (rightRaw != null) rightParts.lastOrNull() else leftParts.lastOrNull()
        if (lastToken != null && lastToken.contains('.')) {
            embeddedIpv4 = parseIpv4(lastToken) ?: return null
            if (rightRaw != null) {
                leftHextets = leftParts
                rightHextets = rightParts.dropLast(1)
            } else {
                leftHextets = leftParts.dropLast(1)
                rightHextets = emptyList()
            }
        } else {
            embeddedIpv4 = null
            leftHextets = leftParts
            rightHextets = rightParts
        }

        val targetHextets = if (embeddedIpv4 != null) 6 else 8
        val totalProvided = leftHextets.size + rightHextets.size
        if (doubleColonIdx >= 0) {
            if (totalProvided >= targetHextets) return null
        } else {
            if (totalProvided != targetHextets) return null
        }

        val out = ByteArray(16)
        var bytePos = 0

        fun writeHextet(token: String): Boolean {
            if (token.isEmpty() || token.length > 4) return false
            val v = token.toIntOrNull(16) ?: return false
            if (v !in 0..0xFFFF) return false
            out[bytePos++] = ((v ushr 8) and 0xFF).toByte()
            out[bytePos++] = (v and 0xFF).toByte()
            return true
        }

        for (t in leftHextets) {
            if (!writeHextet(t)) return null
        }
        val missing = targetHextets - totalProvided
        bytePos += missing * 2
        for (t in rightHextets) {
            if (!writeHextet(t)) return null
        }
        if (embeddedIpv4 != null) {
            for (octet in embeddedIpv4) {
                out[bytePos++] = octet.toByte()
            }
        }
        return if (bytePos == 16) out else null
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v !in 0..255) return null
            octets[i] = v
        }
        return octets
    }
}

/**
 * Hardened network client:
 * - Zero WebView / CookieManager usage.
 * - Rejects plain HTTP to public hosts (cleartext only allowed for localhost / private LAN Torznab).
 * - Supports configurable DNS-over-HTTPS (DoH).
 * - Redacts API keys from any log output.
 */
class HardenedNetworkClient(
    initialDoh: DohProvider = DohProvider.Cloudflare,
    initialNetworkGate: (host: String) -> Boolean = { true },
) {
    private val activeDoh = AtomicReference(initialDoh)
    private val activeNetworkGate = AtomicReference(initialNetworkGate)
    private val bootstrapClient = OkHttpClient.Builder().build()
    private val dnsCache = AtomicReference<Pair<DohProvider, Dns>?>(null)

    private val dynamicDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val provider = activeDoh.get()
            val cached = dnsCache.get()
            val resolver = if (cached != null && cached.first == provider) {
                cached.second
            } else {
                DohProviders.build(provider, bootstrapClient).also { dnsCache.set(provider to it) }
            }
            return resolver.lookup(hostname)
        }
    }

    private val networkGateInterceptor = Interceptor { chain ->
        val req = chain.request()
        val host = req.url.host
        if (!NetworkSafety.isCleartextPermittedHost(host) && !activeNetworkGate.get().invoke(host)) {
            throw MobileDataBlockedException(req.url.toString())
        }
        chain.proceed(req)
    }

    private val httpsEnforcementInterceptor = Interceptor { chain ->
        val req = chain.request()
        if (!req.isHttps && !NetworkSafety.isCleartextPermittedHost(req.url.host)) {
            throw PublicCleartextRejectedException(req.url.toString())
        }
        chain.proceed(req)
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(dynamicDns)
        .addInterceptor(networkGateInterceptor)
        .addInterceptor(httpsEnforcementInterceptor)
        .addNetworkInterceptor(httpsEnforcementInterceptor)
        .build()

    fun setNetworkAllowedGate(gate: (host: String) -> Boolean) {
        activeNetworkGate.set(gate)
    }

    fun lookupHost(hostname: String): List<InetAddress> = dynamicDns.lookup(hostname)

    private val ktorClient = HttpClient(OkHttp) {
        engine { preconfigured = okHttpClient }
        install(UserAgent) { agent = USER_AGENT }
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 2)
            retryOnExceptionIf(maxRetries = 2) { _, cause ->
                cause !is MobileDataBlockedException && cause !is PublicCleartextRejectedException
            }
            exponentialDelay()
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 18_000L
            connectTimeoutMillis = 10_000L
            socketTimeoutMillis = 15_000L
        }
    }

    fun setDohProvider(provider: DohProvider) {
        activeDoh.set(provider)
    }

    fun currentDohProvider(): DohProvider = activeDoh.get()

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val response = get(url, headers)
        return response.bodyAsText()
    }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement? {
        val text = getText(url, headers)
        if (text.isBlank()) return null
        return parseJson(text)
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse {
        val response = ktorClient.get(url) {
            headers.forEach { (k, v) -> header(k, v) }
        }
        validateResponse(response, url)
        return response
    }

    suspend fun postJson(url: String, payload: JsonElement): JsonElement? {
        val response = ktorClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        validateResponse(response, url)
        if (response.contentLength() == 0L) return null
        return parseJson(response.bodyAsText())
    }

    private fun validateResponse(response: HttpResponse, url: String) {
        if (response.headers.contains("cf-mitigated", "challenge") ||
            response.status == HttpStatusCode.Forbidden ||
            response.status == HttpStatusCode.ServiceUnavailable
        ) {
            throw UpstreamBlockedException(url, response.status.value)
        }
    }

    private suspend fun parseJson(raw: String): JsonElement? = withContext(Dispatchers.Default) {
        runCatching { Json.parseToJsonElement(raw) }
            .onFailure { Log.w(TAG, "Invalid JSON payload received") }
            .getOrNull()
    }

    companion object {
        private const val TAG = "HardenedNetworkClient"
        const val USER_AGENT = "CiniTo/1.0 (Android; OpenSource)"
    }
}
