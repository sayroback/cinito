package org.cinito.online.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.cinito.online.metadata.CatalogRegion
import org.cinito.online.network.DohProvider
import org.cinito.online.search.SearchLanguageFilter
import org.cinito.online.search.SecretStorage

enum class SubtitleEdgeStyle(val label: String) {
    DropShadow("Sombra"),
    Outline("Contorno"),
    None("Sin borde"),
}

enum class LibraryViewMode {
    Gallery,
    List,
}

enum class PlayabilityDiagnosticsMode(
    val label: String,
    val description: String,
) {
    SmartScrape("Inteligente (UDP)", "Verifica trackers UDP ligeros (~4 KB) y permite prueba manual."),
    AutoTop3OnWifi("Auto Top 3 (Wi-Fi)", "Diagnostica automáticamente las 3 mejores opciones en Wi-Fi sin medir y con batería saludable."),
    LocalOnly("Solo metadatos", "Cero tráfico extra: ordena únicamente con seeders y calidad reportados."),
}

data class SubtitleStyleConfig(
    val fontSizeSp: Float = 19f,
    val backgroundOpacity: Float = 0.45f,
    val edgeStyle: SubtitleEdgeStyle = SubtitleEdgeStyle.DropShadow,
)

data class AppPreferences(
    val dohProvider: DohProvider = DohProvider.Cloudflare,
    val metadataLookupEnabled: Boolean = true,
    val catalogRegion: CatalogRegion = CatalogRegion.MexicoLatino,
    val searchLanguageFilter: SearchLanguageFilter = SearchLanguageFilter.PrioritizeLatino,
    val playabilityDiagnosticsMode: PlayabilityDiagnosticsMode = PlayabilityDiagnosticsMode.SmartScrape,
    val enabledProviderIds: Set<String> = DEFAULT_PROVIDER_IDS,
    val preferredAudioLanguage: String = "es",
    val preferredSubtitleLanguage: String = "es",
    val subtitlesEnabledByDefault: Boolean = true,
    val subtitleStyle: SubtitleStyleConfig = SubtitleStyleConfig(),
    val defaultPlaybackSpeed: Float = 1.0f,
    val useExternalPlayer: Boolean = false,
    val libraryViewMode: LibraryViewMode = LibraryViewMode.Gallery,
    val showRawFilenames: Boolean = false,
    val allowMobileData: Boolean = false,
    val hasTmdbApiKey: Boolean = false,
    val recentSearchQueries: List<String> = emptyList(),
) {
    companion object {
        val DEFAULT_PROVIDER_IDS: Set<String> = setOf(
            "torrentio",
            "knaben",
            "thepiratebay",
            "solidtorrents",
            "limetorrents",
            "bitsearch",
            "yts",
            "nyaa",
            "torrentscsv",
        )
        internal val V2_NEW_DEFAULT_PROVIDERS: Set<String> = setOf(
            "torrentio",
            "solidtorrents",
            "limetorrents",
        )
    }
}

interface KeyValueStorage {
    fun getString(key: String, default: String?): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getFloat(key: String, default: Float): Float
    fun putFloat(key: String, value: Float)
    fun getStringSet(key: String, default: Set<String>): Set<String>
    fun putStringSet(key: String, value: Set<String>)
}

class SharedPrefsStorage(context: Context) : KeyValueStorage {
    private val prefs = context.getSharedPreferences("cinito_app_prefs", Context.MODE_PRIVATE)

    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    override fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)
    override fun putFloat(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()
    override fun getStringSet(key: String, default: Set<String>): Set<String> =
        prefs.getStringSet(key, default)?.toSet() ?: default
    override fun putStringSet(key: String, value: Set<String>) =
        prefs.edit().putStringSet(key, value).apply()
}

class AppSettingsRepository(
    private val storage: KeyValueStorage,
    private val secretStorage: SecretStorage? = null,
) {
    private val _preferences = MutableStateFlow(load())
    val preferences: StateFlow<AppPreferences> = _preferences.asStateFlow()

    fun getTmdbApiKey(): String? = secretStorage?.readString(KEY_TMDB_API_KEY)?.takeIf { it.isNotBlank() }

    fun setTmdbApiKey(apiKey: String) {
        val clean = apiKey.trim()
        secretStorage?.saveString(KEY_TMDB_API_KEY, clean)
        _preferences.update { it.copy(hasTmdbApiKey = clean.isNotEmpty()) }
    }

    fun setCatalogRegion(region: CatalogRegion) {
        storage.putString(KEY_CATALOG_REGION, region.name)
        _preferences.update { it.copy(catalogRegion = region) }
    }

    fun setSearchLanguageFilter(filter: SearchLanguageFilter) {
        storage.putString(KEY_SEARCH_LANG_FILTER, filter.name)
        _preferences.update { it.copy(searchLanguageFilter = filter) }
    }

    fun setPlayabilityDiagnosticsMode(mode: PlayabilityDiagnosticsMode) {
        storage.putString(KEY_PLAYABILITY_DIAGNOSTICS_MODE, mode.name)
        _preferences.update { it.copy(playabilityDiagnosticsMode = mode) }
    }

    fun setDohProvider(provider: DohProvider) {
        storage.putString(KEY_DOH, provider.name)
        _preferences.update { it.copy(dohProvider = provider) }
    }

    fun setMetadataLookupEnabled(enabled: Boolean) {
        storage.putBoolean(KEY_METADATA_LOOKUP, enabled)
        _preferences.update { it.copy(metadataLookupEnabled = enabled) }
    }

    fun toggleSearchProvider(providerId: String, enabled: Boolean) {
        _preferences.update { current ->
            val next = if (enabled) current.enabledProviderIds + providerId else current.enabledProviderIds - providerId
            storage.putStringSet(KEY_ENABLED_PROVIDERS, next)
            current.copy(enabledProviderIds = next)
        }
    }

    fun setPreferredAudioLanguage(langCode: String) {
        val clean = langCode.trim().lowercase().ifEmpty { "es" }
        storage.putString(KEY_AUDIO_LANG, clean)
        _preferences.update { it.copy(preferredAudioLanguage = clean) }
    }

    fun setPreferredSubtitleLanguage(langCode: String) {
        val clean = langCode.trim().lowercase().ifEmpty { "es" }
        storage.putString(KEY_SUB_LANG, clean)
        _preferences.update { it.copy(preferredSubtitleLanguage = clean) }
    }

    fun setSubtitlesEnabledByDefault(enabled: Boolean) {
        storage.putBoolean(KEY_SUBS_ENABLED, enabled)
        _preferences.update { it.copy(subtitlesEnabledByDefault = enabled) }
    }

    fun updateSubtitleStyle(style: SubtitleStyleConfig) {
        val clamped = style.copy(
            fontSizeSp = style.fontSizeSp.coerceIn(13f, 34f),
            backgroundOpacity = style.backgroundOpacity.coerceIn(0f, 0.9f),
        )
        storage.putFloat(KEY_SUB_FONT_SP, clamped.fontSizeSp)
        storage.putFloat(KEY_SUB_BG_ALPHA, clamped.backgroundOpacity)
        storage.putString(KEY_SUB_EDGE, clamped.edgeStyle.name)
        _preferences.update { it.copy(subtitleStyle = clamped) }
    }

    fun setDefaultPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        storage.putFloat(KEY_DEFAULT_SPEED, clamped)
        _preferences.update { it.copy(defaultPlaybackSpeed = clamped) }
    }

    fun setUseExternalPlayer(external: Boolean) {
        storage.putBoolean(KEY_EXTERNAL_PLAYER, external)
        _preferences.update { it.copy(useExternalPlayer = external) }
    }

    fun setLibraryViewMode(mode: LibraryViewMode) {
        storage.putString(KEY_LIBRARY_MODE, mode.name)
        _preferences.update { it.copy(libraryViewMode = mode) }
    }

    fun setShowRawFilenames(show: Boolean) {
        storage.putBoolean(KEY_RAW_FILENAMES, show)
        _preferences.update { it.copy(showRawFilenames = show) }
    }

    fun setAllowMobileData(allow: Boolean) {
        storage.putBoolean(KEY_ALLOW_MOBILE_DATA, allow)
        _preferences.update { it.copy(allowMobileData = allow) }
    }

    fun addRecentSearchQuery(query: String) {
        val clean = query.trim().replace("\n", " ")
        if (clean.length < 2) return
        _preferences.update { current ->
            val updated = (listOf(clean) + current.recentSearchQueries.filterNot { it.equals(clean, ignoreCase = true) })
                .take(MAX_RECENT_SEARCHES)
            storage.putString(KEY_RECENT_SEARCHES, updated.joinToString("\n"))
            current.copy(recentSearchQueries = updated)
        }
    }

    fun removeRecentSearchQuery(query: String) {
        _preferences.update { current ->
            val updated = current.recentSearchQueries.filterNot { it.equals(query, ignoreCase = true) }
            storage.putString(KEY_RECENT_SEARCHES, updated.joinToString("\n"))
            current.copy(recentSearchQueries = updated)
        }
    }

    fun clearRecentSearchQueries() {
        storage.putString(KEY_RECENT_SEARCHES, "")
        _preferences.update { it.copy(recentSearchQueries = emptyList()) }
    }

    private fun load(): AppPreferences {
        val doh = DohProvider.entries.find { it.name == storage.getString(KEY_DOH, null) }
            ?: DohProvider.Cloudflare
        val catalogRegion = CatalogRegion.entries.find { it.name == storage.getString(KEY_CATALOG_REGION, null) }
            ?: CatalogRegion.MexicoLatino
        val searchLangFilter = SearchLanguageFilter.entries.find { it.name == storage.getString(KEY_SEARCH_LANG_FILTER, null) }
            ?: SearchLanguageFilter.PrioritizeLatino
        val playabilityMode = PlayabilityDiagnosticsMode.entries.find {
            it.name == storage.getString(KEY_PLAYABILITY_DIAGNOSTICS_MODE, null)
        } ?: PlayabilityDiagnosticsMode.SmartScrape
        val edge = SubtitleEdgeStyle.entries.find { it.name == storage.getString(KEY_SUB_EDGE, null) }
            ?: SubtitleEdgeStyle.DropShadow
        val viewMode = LibraryViewMode.entries.find { it.name == storage.getString(KEY_LIBRARY_MODE, null) }
            ?: LibraryViewMode.Gallery
        val savedProviders = storage.getStringSet(KEY_ENABLED_PROVIDERS, AppPreferences.DEFAULT_PROVIDER_IDS)
        val seedVersion = storage.getFloat(KEY_PROVIDER_SEED_VERSION, 1f).toInt()
        val migratedProviders = if (seedVersion < CURRENT_PROVIDER_SEED_VERSION) {
            val merged = savedProviders + AppPreferences.V2_NEW_DEFAULT_PROVIDERS
            storage.putStringSet(KEY_ENABLED_PROVIDERS, merged)
            storage.putFloat(KEY_PROVIDER_SEED_VERSION, CURRENT_PROVIDER_SEED_VERSION.toFloat())
            merged
        } else {
            savedProviders
        }
        return AppPreferences(
            dohProvider = doh,
            metadataLookupEnabled = storage.getBoolean(KEY_METADATA_LOOKUP, true),
            catalogRegion = catalogRegion,
            searchLanguageFilter = searchLangFilter,
            playabilityDiagnosticsMode = playabilityMode,
            enabledProviderIds = migratedProviders,
            preferredAudioLanguage = storage.getString(KEY_AUDIO_LANG, "es") ?: "es",
            preferredSubtitleLanguage = storage.getString(KEY_SUB_LANG, "es") ?: "es",
            subtitlesEnabledByDefault = storage.getBoolean(KEY_SUBS_ENABLED, true),
            subtitleStyle = SubtitleStyleConfig(
                fontSizeSp = storage.getFloat(KEY_SUB_FONT_SP, 19f).coerceIn(13f, 34f),
                backgroundOpacity = storage.getFloat(KEY_SUB_BG_ALPHA, 0.45f).coerceIn(0f, 0.9f),
                edgeStyle = edge,
            ),
            defaultPlaybackSpeed = storage.getFloat(KEY_DEFAULT_SPEED, 1.0f).coerceIn(0.5f, 3.0f),
            useExternalPlayer = storage.getBoolean(KEY_EXTERNAL_PLAYER, false),
            libraryViewMode = viewMode,
            showRawFilenames = storage.getBoolean(KEY_RAW_FILENAMES, false),
            allowMobileData = storage.getBoolean(KEY_ALLOW_MOBILE_DATA, false),
            hasTmdbApiKey = !getTmdbApiKey().isNullOrBlank(),
            recentSearchQueries = storage.getString(KEY_RECENT_SEARCHES, null)
                ?.lineSequence()
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.take(MAX_RECENT_SEARCHES)
                ?.toList()
                .orEmpty(),
        )
    }

    companion object {
        private const val KEY_DOH = "doh_provider"
        private const val KEY_METADATA_LOOKUP = "metadata_lookup_enabled"
        private const val KEY_CATALOG_REGION = "catalog_region"
        private const val KEY_SEARCH_LANG_FILTER = "search_language_filter"
        private const val KEY_PLAYABILITY_DIAGNOSTICS_MODE = "playability_diagnostics_mode"
        private const val KEY_TMDB_API_KEY = "tmdb_api_key_v1"
        private const val KEY_ENABLED_PROVIDERS = "enabled_provider_ids"
        private const val KEY_PROVIDER_SEED_VERSION = "provider_seed_version"
        private const val CURRENT_PROVIDER_SEED_VERSION = 2
        private const val KEY_AUDIO_LANG = "preferred_audio_lang"
        private const val KEY_SUB_LANG = "preferred_sub_lang"
        private const val KEY_SUBS_ENABLED = "subs_enabled_default"
        private const val KEY_SUB_FONT_SP = "sub_font_size_sp"
        private const val KEY_SUB_BG_ALPHA = "sub_bg_alpha"
        private const val KEY_SUB_EDGE = "sub_edge_style"
        private const val KEY_DEFAULT_SPEED = "default_playback_speed"
        private const val KEY_EXTERNAL_PLAYER = "use_external_player"
        private const val KEY_LIBRARY_MODE = "library_view_mode"
        private const val KEY_RAW_FILENAMES = "show_raw_filenames"
        private const val KEY_ALLOW_MOBILE_DATA = "allow_mobile_data"
        private const val KEY_RECENT_SEARCHES = "recent_search_queries_v1"
        private const val MAX_RECENT_SEARCHES = 10
    }
}
