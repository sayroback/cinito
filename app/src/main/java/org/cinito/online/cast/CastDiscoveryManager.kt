package org.cinito.online.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.cinito.online.network.HardenedNetworkClient
import org.cinito.online.network.NetworkSafety
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Discovers Smart TVs and Cast receivers across the local Wi-Fi network using:
 * 1. Android NsdManager mDNS (`_googlecast._tcp.`) for Chromecast / Google TV / Android TV
 * 2. SSDP multicast & unicast for LG webOS Second Screen (`webos-second-screen:1`), DLNA (`MediaRenderer:1`), and DIAL
 * 3. Direct LAN IP probing (`probeHost`) for subnets/routers that block multicast UDP
 */
class CastDiscoveryManager(
    context: Context,
    private val networkClient: HardenedNetworkClient,
) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val devicesLock = Any()
    private var deviceList = emptyList<CastDevice>()
    private val resolvedLocations = ConcurrentHashMap.newKeySet<String>()
    private val inFlightLocations = ConcurrentHashMap.newKeySet<String>()
    private val inFlightResolves = ConcurrentHashMap.newKeySet<String>()
    private val nsdNameToDeviceId = ConcurrentHashMap<String, String>()
    private val resolveSemaphore = Semaphore(3)
    private val discoveryGeneration = AtomicLong(0L)

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private var discoveryJob: Job? = null
    private var resolveWorkerJob: Job? = null
    private var refreshJob: Job? = null
    private var resolveQueue: Channel<NsdServiceInfo>? = null
    private var activeNsdListener: NsdManager.DiscoveryListener? = null
    @Volatile private var nsdStoppedSignal: CompletableDeferred<Unit>? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun startDiscovery() {
        if (_isDiscovering.value) return
        refreshJob?.cancel()
        refreshJob = null
        discoveryGeneration.incrementAndGet()
        _isDiscovering.value = true

        runCatching {
            val lock = wifiManager?.createMulticastLock("CiniToCastDiscovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = lock
        }

        startGoogleCastMdns()
        startSsdpScan()
    }

    fun refreshDiscovery() {
        val pendingStop = nsdStoppedSignal
        stopDiscovery()
        val refreshGen = discoveryGeneration.incrementAndGet()
        refreshJob = scope.launch {
            if (pendingStop != null) {
                withTimeoutOrNull(450L) { pendingStop.await() }
            } else {
                delay(100L)
            }
            if (discoveryGeneration.get() == refreshGen) {
                startDiscovery()
            }
        }
    }

    fun stopDiscovery() {
        discoveryGeneration.incrementAndGet()
        refreshJob?.cancel()
        refreshJob = null
        _isDiscovering.value = false
        resolvedLocations.clear()
        inFlightLocations.clear()
        inFlightResolves.clear()
        val listener = activeNsdListener
        activeNsdListener = null
        if (listener != null) {
            runCatching { nsdManager?.stopServiceDiscovery(listener) }
                .onFailure { nsdStoppedSignal?.complete(Unit) }
        } else {
            nsdStoppedSignal?.complete(Unit)
        }
        resolveWorkerJob?.cancel()
        resolveWorkerJob = null
        resolveQueue?.close()
        resolveQueue = null
        discoveryJob?.cancel()
        discoveryJob = null
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
        multicastLock = null
    }

    suspend fun probeHost(rawHost: String): CastDevice? = withContext(Dispatchers.IO) {
        val host = rawHost.trim().trim('[', ']').substringBefore('%')
        if (!NetworkSafety.isPrivateLanHost(host)) return@withContext null
        val urlHost = if (host.contains(':')) "[$host]" else host

        val found = coroutineScope {
            val ssdpJob = async { queryHostViaUnicastSsdp(host) }
            val ssap3000 = async { isTcpPortOpen(host, 3000) }
            val ssap3001 = async { isTcpPortOpen(host, 3001) }
            val gcast8009 = async { isTcpPortOpen(host, 8009) }
            val dialDesc = async {
                runCatching {
                    val descUrl = "http://$urlHost:8008/ssdp/device-desc.xml"
                    val xml = networkClient.getText(descUrl)
                    CastProtocolUtils.parseUpnpDeviceDescription(xml, descUrl)
                }.getOrNull()
            }
            val eurekaInfo = async {
                runCatching {
                    networkClient.getJson("http://$urlHost:8008/setup/eureka_info") as? JsonObject
                }.getOrNull()
            }

            val candidates = mutableListOf<CastDevice>()
            candidates.addAll(ssdpJob.await())
            dialDesc.await()?.let { candidates += it }

            val openSsapPort = when {
                ssap3000.await() -> 3000
                ssap3001.await() -> 3001
                else -> null
            }
            if (openSsapPort != null) {
                if (candidates.none { it.avTransportControlUrl != null }) {
                    candidates.addAll(queryHostViaMulticastSsdpBurst(host))
                }
                candidates += CastDevice(
                    id = "webos:$host",
                    name = "LG webOS TV ($host)",
                    model = null,
                    host = host,
                    port = openSsapPort,
                    protocol = CastProtocol.WebOs,
                    vendor = CastVendor.LG,
                    ssapPort = openSsapPort,
                )
            }
            val eurekaObj = eurekaInfo.await()
            if (gcast8009.await() && candidates.none { it.protocol == CastProtocol.GoogleCast }) {
                val eurekaName = eurekaObj?.get("name")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                val eurekaModel = eurekaObj?.get("model_name")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                val eurekaUdn = eurekaObj?.get("ssdp_udn")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                candidates += CastDevice(
                    id = "gcast:${eurekaUdn ?: host}",
                    name = eurekaName ?: "Chromecast / Google TV ($host)",
                    model = eurekaModel,
                    host = host,
                    port = 8009,
                    protocol = CastProtocol.GoogleCast,
                    vendor = CastVendor.Google,
                )
            }
            candidates
        }

        if (found.isEmpty()) {
            CastLog.w("Discovery", "probeHost($host) found=0")
            return@withContext null
        }
        CastLog.i("Discovery", "probeHost($host) found=${found.joinToString { "${it.protocol}:${it.port}(dlna=${it.hasDlna})" }}")
        found.forEach { publishDevice(it) }
        synchronized(devicesLock) {
            deviceList.firstOrNull { it.host == host }
        }
    }

    private fun publishDevice(device: CastDevice) {
        synchronized(devicesLock) {
            deviceList = CastProtocolUtils.mergeDiscoveredDevice(deviceList, device)
            _devices.value = deviceList
        }
    }

    private fun removeDeviceById(id: String) {
        synchronized(devicesLock) {
            deviceList = deviceList.filterNot { it.id == id }
            _devices.value = deviceList
        }
    }

    private fun startGoogleCastMdns() {
        val mgr = nsdManager ?: return
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        resolveQueue = queue

        resolveWorkerJob = scope.launch {
            for (serviceInfo in queue) {
                val key = serviceInfo.serviceName
                if (!inFlightResolves.add(key)) continue
                launch {
                    try {
                        resolveSemaphore.withPermit {
                            resolveSingleCastService(mgr, serviceInfo)
                        }
                    } finally {
                        inFlightResolves.remove(key)
                    }
                }
            }
        }

        val stoppedSignal = CompletableDeferred<Unit>()
        nsdStoppedSignal = stoppedSignal

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) {
                stoppedSignal.complete(Unit)
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                stoppedSignal.complete(Unit)
                runCatching { mgr.stopServiceDiscovery(this) }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                stoppedSignal.complete(Unit)
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                queue.trySend(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val id = nsdNameToDeviceId.remove(serviceInfo.serviceName) ?: "gcast:${serviceInfo.serviceName}"
                removeDeviceById(id)
            }
        }

        activeNsdListener = listener
        runCatching {
            mgr.discoverServices("_googlecast._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            stoppedSignal.complete(Unit)
        }
    }

    private suspend fun resolveSingleCastService(mgr: NsdManager, info: NsdServiceInfo) {
        var resolved: NsdServiceInfo? = null
        for (attempt in 0 until 2) {
            val latch = CompletableDeferred<Pair<NsdServiceInfo?, Int?>>()
            @Suppress("DEPRECATION")
            runCatching {
                mgr.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        latch.complete(null to errorCode)
                    }

                    override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                        latch.complete(resolvedInfo to null)
                    }
                })
            }.onFailure {
                latch.complete(null to null)
            }

            val (candidate, errorCode) = withTimeoutOrNull(2_500L) { latch.await() } ?: (null to null)
            if (candidate != null) {
                resolved = candidate
                break
            }
            if (attempt == 0 && (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE || errorCode == null)) {
                delay(250L)
            } else {
                break
            }
        }
        val resolvedInfo = resolved ?: return
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolvedInfo.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(resolvedInfo.host)
        }
        val selectedHost = pickBestHostAddress(addresses) ?: return
        val port = resolvedInfo.port.takeIf { it > 0 } ?: 8009
        val attrs = resolvedInfo.attributes
        val friendlyName = attrs["fn"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
            ?: resolvedInfo.serviceName.substringBefore('-')
        val modelName = attrs["md"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
        val uniqueId = attrs["id"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
            ?: resolvedInfo.serviceName
        val deviceId = "gcast:$uniqueId"
        nsdNameToDeviceId[resolvedInfo.serviceName] = deviceId

        publishDevice(
            CastDevice(
                id = deviceId,
                name = friendlyName,
                model = modelName,
                host = selectedHost,
                port = port,
                protocol = CastProtocol.GoogleCast,
                vendor = CastVendor.Google,
            ),
        )
    }

    private fun pickBestHostAddress(addresses: List<InetAddress>): String? {
        val ipv4 = addresses.firstOrNull { addr ->
            addr is Inet4Address && !addr.isLoopbackAddress &&
                addr.hostAddress?.let { NetworkSafety.isPrivateLanHost(it) } == true
        }?.hostAddress
        if (ipv4 != null) return ipv4

        val ipv6Candidates = addresses.asSequence()
            .filter { !it.isLoopbackAddress }
            .mapNotNull { addr ->
                addr.hostAddress?.substringBefore('%')?.takeIf { NetworkSafety.isPrivateLanHost(it) }
            }
            .toList()
        return ipv6Candidates.firstOrNull { !it.startsWith("fe80", ignoreCase = true) }
            ?: ipv6Candidates.firstOrNull()
    }

    private fun createSsdpMulticastSocket(): MulticastSocket {
        val localIp = CastMediaServer.findLocalLanIpv4Address()
        val localAddr = localIp?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
        val netIf = localAddr?.let { runCatching { NetworkInterface.getByInetAddress(it) }.getOrNull() }
        val sock = if (localAddr != null) {
            runCatching { MulticastSocket(InetSocketAddress(localAddr, 0)) }.getOrElse { MulticastSocket() }
        } else {
            MulticastSocket()
        }
        if (netIf != null) {
            runCatching { sock.networkInterface = netIf }
        }
        runCatching { sock.timeToLive = 4 }
        return sock
    }

    private fun startSsdpScan() {
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            while (isActive && _isDiscovering.value) {
                runCatching {
                    createSsdpMulticastSocket().use { socket ->
                        socket.soTimeout = 2_200
                        socket.broadcast = true
                        val group = InetAddress.getByName("239.255.255.250")
                        for (st in SSDP_TARGETS) {
                            val req = buildSsdpSearchPacket(st)
                            socket.send(DatagramPacket(req, req.size, group, 1900))
                        }

                        val deadline = System.currentTimeMillis() + 3_200L
                        val buf = ByteArray(4096)
                        while (System.currentTimeMillis() < deadline && isActive) {
                            val packet = DatagramPacket(buf, buf.size)
                            val received = runCatching { socket.receive(packet); true }.getOrDefault(false)
                            if (!received) break
                            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            val location = CastProtocolUtils.parseSsdpLocationUrl(text) ?: continue
                            if (!resolvedLocations.contains(location) && inFlightLocations.add(location)) {
                                launch {
                                    try {
                                        val xml = runCatching { networkClient.getText(location) }.getOrNull() ?: return@launch
                                        if (xml.isNotBlank() && resolvedLocations.size < 256) {
                                            resolvedLocations.add(location)
                                        }
                                        val device = CastProtocolUtils.parseUpnpDeviceDescription(xml, location)
                                        if (device != null) {
                                            publishDevice(device)
                                        }
                                    } finally {
                                        inFlightLocations.remove(location)
                                    }
                                }
                            }
                        }
                    }
                }
                delay(5_000L)
            }
        }
    }

    private suspend fun queryHostViaMulticastSsdpBurst(host: String): List<CastDevice> {
        val targetIp = runCatching { InetAddress.getByName(host).hostAddress?.substringBefore('%') }.getOrNull() ?: host
        val foundLocations = LinkedHashSet<String>()
        runCatching {
            createSsdpMulticastSocket().use { socket ->
                socket.soTimeout = 900
                socket.broadcast = true
                val group = InetAddress.getByName("239.255.255.250")
                for (st in SSDP_TARGETS) {
                    val req = buildSsdpSearchPacket(st)
                    socket.send(DatagramPacket(req, req.size, group, 1900))
                }
                val deadline = System.currentTimeMillis() + 1_600L
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    val ok = runCatching { socket.receive(packet); true }.getOrDefault(false)
                    if (!ok) break
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val location = CastProtocolUtils.parseSsdpLocationUrl(text) ?: continue
                    val packetIp = packet.address?.hostAddress?.substringBefore('%')
                    val locHost = runCatching { URI(location).host?.trim('[', ']')?.substringBefore('%') }.getOrNull()
                    if (packetIp.equals(targetIp, ignoreCase = true) || locHost.equals(host, ignoreCase = true)) {
                        foundLocations += location
                    }
                }
            }
        }
        return foundLocations.mapNotNull { loc ->
            val xml = runCatching { networkClient.getText(loc) }.getOrNull() ?: return@mapNotNull null
            CastProtocolUtils.parseUpnpDeviceDescription(xml, loc)
        }
    }

    private suspend fun queryHostViaUnicastSsdp(host: String): List<CastDevice> {
        val foundLocations = LinkedHashSet<String>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 1_500
                val targetAddr = InetAddress.getByName(host)
                for (st in SSDP_TARGETS) {
                    val req = buildSsdpSearchPacket(st)
                    socket.send(DatagramPacket(req, req.size, targetAddr, 1900))
                }
                val deadline = System.currentTimeMillis() + 1_800L
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    val ok = runCatching { socket.receive(packet); true }.getOrDefault(false)
                    if (!ok) break
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    CastProtocolUtils.parseSsdpLocationUrl(text)?.let { foundLocations += it }
                }
            }
        }
        return foundLocations.mapNotNull { loc ->
            val xml = runCatching { networkClient.getText(loc) }.getOrNull() ?: return@mapNotNull null
            CastProtocolUtils.parseUpnpDeviceDescription(xml, loc)
        }
    }

    private fun isTcpPortOpen(host: String, port: Int): Boolean = runCatching {
        Socket().use { sock ->
            sock.connect(InetSocketAddress(host, port), 1_500)
            true
        }
    }.getOrDefault(false)

    private fun buildSsdpSearchPacket(st: String): ByteArray = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: 239.255.255.250:1900\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: 2\r\n")
        append("ST: $st\r\n\r\n")
    }.toByteArray(Charsets.UTF_8)

    companion object {
        private val SSDP_TARGETS = listOf(
            "urn:lge-com:service:webos-second-screen:1",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:dial-multiscreen-org:service:dial:1",
        )
    }
}
