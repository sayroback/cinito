package webtor.app

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/** Process-owned callback; Android delivers capabilities after onAvailable. */
class NetworkMonitor(
    private val manager: ConnectivityManager,
    private val onChange: (NetworkSnapshot) -> Unit,
) : ConnectivityManager.NetworkCallback() {
    private val handler = Handler(Looper.getMainLooper())
    private val state = DefaultNetworkState()
    private var published: NetworkSnapshot? = null
    private var pendingValidationNetwork: Long? = null
    private var pendingValidationCaps: PendingCapabilities? = null
    private var pendingValidationRunnable: Runnable? = null

    private data class PendingCapabilities(
        val networkHandle: Long,
        val wifi: Boolean,
        val metered: Boolean,
        val cellular: Boolean,
    )

    fun start() {
        publish(state.snapshot)
        manager.registerDefaultNetworkCallback(this, handler)
    }

    fun stop() {
        clearPendingValidationLoss()
        runCatching { manager.unregisterNetworkCallback(this) }
    }

    override fun onAvailable(network: Network) {
        if (pendingValidationNetwork != null && pendingValidationNetwork != network.networkHandle) {
            clearPendingValidationLoss()
        }
        publish(state.available(network.networkHandle))
    }

    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        val isWifiOrWired = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !isWifiOrWired
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = hasInternet && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        if (validated) {
            clearPendingValidationLoss()
            publish(
                state.capabilities(
                    network.networkHandle,
                    validated = true,
                    wifi = isWifiOrWired,
                    metered = metered,
                    cellular = isCellular,
                ),
            )
            return
        }

        if (published?.connected == true && state.snapshot.connected && hasInternet && isWifiOrWired) {
            // Defer momentary NET_CAPABILITY_VALIDATED drops on Wi-Fi/Ethernet so
            // DefaultNetworkState does not bump generation and tear down peers on a blip.
            scheduleValidationLoss(
                PendingCapabilities(
                    networkHandle = network.networkHandle,
                    wifi = isWifiOrWired,
                    metered = metered,
                    cellular = isCellular,
                ),
            )
            return
        }

        clearPendingValidationLoss()
        publish(
            state.capabilities(
                network.networkHandle,
                validated = false,
                wifi = isWifiOrWired,
                metered = metered,
                cellular = isCellular,
            ),
        )
    }

    override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = publish(
        state.links(network.networkHandle, listOf(
            properties.interfaceName.orEmpty(),
            properties.linkAddresses.map { it.toString() }.sorted().joinToString(),
            properties.routes.map { it.toString() }.sorted().joinToString(),
            properties.dnsServers.map { it.hostAddress.orEmpty() }.sorted().joinToString(),
        ).joinToString("|")),
    )

    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
        if (blocked) clearPendingValidationLoss()
        publish(state.blocked(network.networkHandle, blocked))
    }

    override fun onLost(network: Network) {
        clearPendingValidationLoss()
        publish(state.lost(network.networkHandle))
    }

    private fun scheduleValidationLoss(pending: PendingCapabilities) {
        pendingValidationNetwork = pending.networkHandle
        pendingValidationCaps = pending
        if (pendingValidationRunnable != null) return
        val runnable = Runnable {
            val caps = pendingValidationCaps
            pendingValidationRunnable = null
            pendingValidationNetwork = null
            pendingValidationCaps = null
            if (caps != null) {
                publish(
                    state.capabilities(
                        caps.networkHandle,
                        validated = false,
                        wifi = caps.wifi,
                        metered = caps.metered,
                        cellular = caps.cellular,
                    ),
                )
            }
        }
        pendingValidationRunnable = runnable
        handler.postDelayed(runnable, VALIDATION_LOSS_GRACE_MS)
    }

    private fun clearPendingValidationLoss() {
        pendingValidationRunnable?.let { handler.removeCallbacks(it) }
        pendingValidationRunnable = null
        pendingValidationNetwork = null
        pendingValidationCaps = null
    }

    private fun publish(snapshot: NetworkSnapshot) {
        if (published == snapshot) return
        published = snapshot
        onChange(snapshot)
    }

    private companion object {
        const val VALIDATION_LOSS_GRACE_MS = 1_500L
    }
}
