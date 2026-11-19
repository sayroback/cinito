package org.cinito.online.settings

import org.cinito.online.metadata.CatalogRegion
import org.cinito.online.network.DohProvider
import org.cinito.online.search.SearchLanguageFilter
import org.cinito.online.search.SecretStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsRepositoryTest {
    private class InMemoryStorage : KeyValueStorage {
        private val strings = mutableMapOf<String, String>()
        private val booleans = mutableMapOf<String, Boolean>()
        private val floats = mutableMapOf<String, Float>()
        private val sets = mutableMapOf<String, Set<String>>()

        override fun getString(key: String, default: String?): String? = strings[key] ?: default
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun getBoolean(key: String, default: Boolean): Boolean = booleans[key] ?: default
        override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
        override fun getFloat(key: String, default: Float): Float = floats[key] ?: default
        override fun putFloat(key: String, value: Float) { floats[key] = value }
        override fun getStringSet(key: String, default: Set<String>): Set<String> = sets[key] ?: default
        override fun putStringSet(key: String, value: Set<String>) { sets[key] = value }
    }

    private class InMemorySecretStorage : SecretStorage {
        private val secrets = mutableMapOf<String, String>()
        override fun saveString(key: String, value: String) { secrets[key] = value }
        override fun readString(key: String): String? = secrets[key]
    }

    @Test
    fun persistsUpdatesAndClampsPlayerPreferences() {
        val storage = InMemoryStorage()
        val secrets = InMemorySecretStorage()
        val repo = AppSettingsRepository(storage, secrets)

        assertEquals(DohProvider.Cloudflare, repo.preferences.value.dohProvider)
        assertEquals(CatalogRegion.MexicoLatino, repo.preferences.value.catalogRegion)
        assertEquals(SearchLanguageFilter.PrioritizeLatino, repo.preferences.value.searchLanguageFilter)
        assertEquals(PlayabilityDiagnosticsMode.SmartScrape, repo.preferences.value.playabilityDiagnosticsMode)
        assertTrue("torrentio" in repo.preferences.value.enabledProviderIds)
        assertTrue("thepiratebay" in repo.preferences.value.enabledProviderIds)
        assertTrue("solidtorrents" in repo.preferences.value.enabledProviderIds)
        assertTrue("limetorrents" in repo.preferences.value.enabledProviderIds)
        assertTrue("bitsearch" in repo.preferences.value.enabledProviderIds)
        assertFalse(repo.preferences.value.hasTmdbApiKey)
        assertTrue(repo.preferences.value.metadataLookupEnabled)
        assertFalse(repo.preferences.value.useExternalPlayer)
        assertFalse(repo.preferences.value.allowMobileData)

        repo.setDohProvider(DohProvider.Quad9)
        repo.setMetadataLookupEnabled(false)
        repo.setAllowMobileData(true)
        repo.setCatalogRegion(CatalogRegion.GlobalEnglish)
        repo.setSearchLanguageFilter(SearchLanguageFilter.OnlyLatino)
        repo.setPlayabilityDiagnosticsMode(PlayabilityDiagnosticsMode.AutoTop3OnWifi)
        repo.setTmdbApiKey("  my-secret-tmdb  ")
        repo.setPreferredAudioLanguage("ja")
        repo.setPreferredSubtitleLanguage("es")
        repo.setDefaultPlaybackSpeed(4.5f)
        repo.updateSubtitleStyle(SubtitleStyleConfig(fontSizeSp = 40f, backgroundOpacity = -1f, edgeStyle = SubtitleEdgeStyle.Outline))

        val reloadedRepo = AppSettingsRepository(storage, secrets)
        val reloaded = reloadedRepo.preferences.value
        assertEquals(DohProvider.Quad9, reloaded.dohProvider)
        assertEquals(CatalogRegion.GlobalEnglish, reloaded.catalogRegion)
        assertEquals(SearchLanguageFilter.OnlyLatino, reloaded.searchLanguageFilter)
        assertEquals(PlayabilityDiagnosticsMode.AutoTop3OnWifi, reloaded.playabilityDiagnosticsMode)
        assertTrue(reloaded.hasTmdbApiKey)
        assertEquals("my-secret-tmdb", reloadedRepo.getTmdbApiKey())
        assertFalse(reloaded.metadataLookupEnabled)
        assertTrue(reloaded.allowMobileData)
        assertEquals("ja", reloaded.preferredAudioLanguage)
        assertEquals(3.0f, reloaded.defaultPlaybackSpeed)
        assertEquals(34f, reloaded.subtitleStyle.fontSizeSp)
        assertEquals(0f, reloaded.subtitleStyle.backgroundOpacity)
        assertEquals(SubtitleEdgeStyle.Outline, reloaded.subtitleStyle.edgeStyle)

        reloadedRepo.setTmdbApiKey("")
        assertFalse(reloadedRepo.preferences.value.hasTmdbApiKey)
        assertNull(reloadedRepo.getTmdbApiKey())
    }

    @Test
    fun migratesV1SavedProvidersOnceWithoutReEnablingAfterUserToggle() {
        val storage = InMemoryStorage().apply {
            putStringSet("enabled_provider_ids", setOf("knaben", "thepiratebay", "bitsearch"))
        }
        val upgraded = AppSettingsRepository(storage)
        val idsAfterUpgrade = upgraded.preferences.value.enabledProviderIds
        assertTrue("torrentio" in idsAfterUpgrade)
        assertTrue("solidtorrents" in idsAfterUpgrade)
        assertTrue("limetorrents" in idsAfterUpgrade)
        // Does not force-re-enable v1 providers that user opted out of
        assertFalse("yts" in idsAfterUpgrade)

        // User disables torrentio after migration; reload keeps it disabled
        upgraded.toggleSearchProvider("torrentio", false)
        val reloaded = AppSettingsRepository(storage)
        assertFalse("torrentio" in reloaded.preferences.value.enabledProviderIds)
    }

    @Test
    fun persistsDeduplicatedRecentSearchQueries() {
        val storage = InMemoryStorage()
        val repo = AppSettingsRepository(storage)

        repo.addRecentSearchQuery("Inception")
        repo.addRecentSearchQuery("Dune Part Two")
        repo.addRecentSearchQuery("inception")
        repo.addRecentSearchQuery(" ") // ignored

        assertEquals(listOf("inception", "Dune Part Two"), repo.preferences.value.recentSearchQueries)

        val reloaded = AppSettingsRepository(storage)
        assertEquals(listOf("inception", "Dune Part Two"), reloaded.preferences.value.recentSearchQueries)

        reloaded.removeRecentSearchQuery("Dune Part Two")
        assertEquals(listOf("inception"), reloaded.preferences.value.recentSearchQueries)

        reloaded.clearRecentSearchQueries()
        assertTrue(reloaded.preferences.value.recentSearchQueries.isEmpty())
    }
}
