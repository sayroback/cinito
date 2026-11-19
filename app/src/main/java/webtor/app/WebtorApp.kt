package webtor.app

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.cinito.online.cast.CastManager
import org.cinito.online.metadata.AudioTrackProbeRepository
import org.cinito.online.metadata.MetadataRepository
import org.cinito.online.network.HardenedNetworkClient
import org.cinito.online.network.NetworkSafety
import org.cinito.online.search.KeystoreSecretStorage
import org.cinito.online.search.SearchCategory
import org.cinito.online.search.SearchService
import org.cinito.online.search.SearchTitleResolution
import org.cinito.online.search.TorznabConfigStore
import org.cinito.online.settings.AppSettingsRepository
import org.cinito.online.settings.SharedPrefsStorage
import webtor.core.EngineClient
import java.lang.ref.WeakReference

class WebtorApp : Application(), SingletonImageLoader.Factory {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val playbackLeases = PlaybackLeases { if (::session.isInitialized) session.refreshTransferNotification() }
    val httpPlaybackLease = HttpPlaybackLease(playbackLeases)
    private var networkMonitor: NetworkMonitor? = null

    private val _networkSnapshotState = MutableStateFlow(NetworkSnapshot())
    val networkSnapshotState: StateFlow<NetworkSnapshot> = _networkSnapshotState.asStateFlow()

    @Volatile
    var latestNetworkSnapshot: NetworkSnapshot = NetworkSnapshot()
        private set

    fun isAppNetworkAllowed(host: String? = null): Boolean {
        if (!host.isNullOrBlank() && NetworkSafety.isCleartextPermittedHost(host)) {
            return true
        }
        val snap = latestNetworkSnapshot
        if (!snap.connected) return false
        val prefs = if (::appSettings.isInitialized) appSettings.preferences.value else null
        val allowMobile = prefs?.allowMobileData ?: false
        val policy = if (::session.isInitialized) {
            session.ui.value.networkPolicy
        } else if (allowMobile) {
            TransferNetworkPolicy.ANY
        } else {
            TransferNetworkPolicy.WIFI_ONLY
        }
        val effectivePolicy = if (!allowMobile && policy == TransferNetworkPolicy.ANY) {
            TransferNetworkPolicy.WIFI_ONLY
        } else {
            policy
        }
        return transferAllowed(effectivePolicy, snap.connected, snap.wifi, snap.metered)
    }

    fun setAllowMobileDataEnabled(allow: Boolean) {
        appSettings.setAllowMobileData(allow)
        val targetPolicy = if (allow) TransferNetworkPolicy.ANY else TransferNetworkPolicy.WIFI_ONLY
        if (::session.isInitialized) {
            session.setNetworkPolicy(targetPolicy)
        }
    }

    fun setTransferNetworkPolicySynced(policy: TransferNetworkPolicy) {
        if (::appSettings.isInitialized) {
            appSettings.setAllowMobileData(policy == TransferNetworkPolicy.ANY)
        }
        if (::session.isInitialized) {
            session.setNetworkPolicy(policy)
        }
    }

    lateinit var engine: EngineHost
        private set
    lateinit var engineClient: EngineClient
        private set
    lateinit var session: LibrarySession
        private set
    lateinit var thumbnails: ThumbnailRepository
        private set

    lateinit var appSettings: AppSettingsRepository
        private set
    lateinit var networkClient: HardenedNetworkClient
        private set
    lateinit var metadataRepository: MetadataRepository
        private set
    lateinit var torznabStore: TorznabConfigStore
        private set
    lateinit var searchService: SearchService
        private set
    lateinit var castManager: CastManager
        private set
    lateinit var audioProbeRepository: AudioTrackProbeRepository
        private set

    @Volatile private var activityRef: WeakReference<Activity>? = null

    fun setCurrentActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun clearCurrentActivity(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    fun currentActivity(): Activity? = activityRef?.get()

    override fun onCreate() {
        super.onCreate()
        val secretStorage = KeystoreSecretStorage(this)
        appSettings = AppSettingsRepository(SharedPrefsStorage(this), secretStorage)
        val initialPrefs = appSettings.preferences.value
        networkClient = HardenedNetworkClient(
            initialDoh = initialPrefs.dohProvider,
            initialNetworkGate = { host -> isAppNetworkAllowed(host) },
        )
        metadataRepository = MetadataRepository(
            network = networkClient,
            cacheDir = cacheDir.resolve("metadata_cache"),
        ).apply {
            lookupEnabled = initialPrefs.metadataLookupEnabled
            catalogRegion = initialPrefs.catalogRegion
            tmdbApiKey = appSettings.getTmdbApiKey()
            canUseNetwork = { isAppNetworkAllowed(null) }
        }
        torznabStore = TorznabConfigStore(secretStorage)
        searchService = SearchService(
            network = networkClient,
            titleResolver = { query, category ->
                val typeHint = when (category) {
                    SearchCategory.Movies -> "movie"
                    SearchCategory.Series -> "series"
                    else -> null
                }
                metadataRepository.resolveSearchQuery(query, typeHint)?.let { meta ->
                    SearchTitleResolution(
                        displayTitle = meta.title,
                        originalTitle = meta.originalTitle,
                        year = meta.year,
                        imdbId = meta.id.takeIf { it.startsWith("tt") },
                        mediaType = meta.type,
                    )
                }
            },
        )

        engine = EngineHost(this)
        engineClient = EngineClient(engine)
        session = LibrarySession(this, engineClient, engine)
        if (!initialPrefs.allowMobileData && session.ui.value.networkPolicy == TransferNetworkPolicy.ANY) {
            session.setNetworkPolicy(TransferNetworkPolicy.WIFI_ONLY)
        } else if (initialPrefs.allowMobileData && session.ui.value.networkPolicy == TransferNetworkPolicy.WIFI_ONLY) {
            appSettings.setAllowMobileData(false)
        }
        audioProbeRepository = AudioTrackProbeRepository.createDefault(
            appContext = this,
            engine = engine,
            engineClient = engineClient,
            activeInfoHashesProvider = {
                session.ui.value.library.mapNotNull { AudioTrackProbeRepository.extractEntryInfoHash(it) }
                    .map { it.lowercase() }
                    .toSet()
            },
            canUseNetwork = { isAppNetworkAllowed(null) },
        )
        thumbnails = ThumbnailRepository(this, engine)
        castManager = CastManager(
            context = this,
            networkClient = networkClient,
            playbackLeases = playbackLeases,
            onProgressBookmark = { bookmarkUri, posMs, durMs ->
                session.recordPlaybackPosition(bookmarkUri, posMs, durMs, allowAutoNext = false)
            },
        )
        engine.start()
        session.start()
        observeCastForService()
        val cm = getSystemService(ConnectivityManager::class.java)
        networkMonitor = NetworkMonitor(cm) { snapshot ->
            latestNetworkSnapshot = snapshot
            _networkSnapshotState.value = snapshot
            session.updateNetworkState(snapshot)
        }
        runCatching { networkMonitor!!.start() }
            .onFailure { Log.e(EngineHost.TAG, "Could not monitor network changes", it) }
    }

    private fun observeCastForService() {
        appScope.launch {
            data class CastServiceKey(
                val isConnected: Boolean,
                val deviceName: String?,
                val activeSourceUri: String?,
                val mediaTitle: String,
            )
            var previous: CastServiceKey? = null
            castManager.session.collect { cast ->
                val current = CastServiceKey(
                    isConnected = cast.isConnected,
                    deviceName = cast.connectedDevice?.name,
                    activeSourceUri = cast.activeSourceUri,
                    mediaTitle = cast.mediaTitle,
                )
                if (current != previous) {
                    val shouldNotify = previous != null || current.isConnected
                    previous = current
                    if (shouldNotify) {
                        session.refreshTransferNotification()
                    }
                }
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { networkClient.okHttpClient }))
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("poster_cache"))
                    .maxSizeBytes(96L * 1024L * 1024L)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
