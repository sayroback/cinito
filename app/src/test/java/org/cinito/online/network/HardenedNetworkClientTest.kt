package org.cinito.online.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardenedNetworkClientTest {

    @Test
    fun cleartextAllowedOnlyForLoopbackAndPrivateLan() {
        assertTrue(NetworkSafety.isCleartextPermittedHost("127.0.0.1"))
        assertTrue(NetworkSafety.isCleartextPermittedHost("localhost"))
        assertTrue(NetworkSafety.isCleartextPermittedHost("::1"))
        assertTrue(NetworkSafety.isCleartextPermittedHost("192.168.1.50"))
        assertTrue(NetworkSafety.isCleartextPermittedHost("10.0.0.12"))
        assertTrue(NetworkSafety.isCleartextPermittedHost("172.16.4.2"))
        assertTrue(NetworkSafety.isCleartextPermittedHost("prowlarr.local"))

        assertFalse(NetworkSafety.isCleartextPermittedHost("example.com"))
        assertFalse(NetworkSafety.isCleartextPermittedHost("172.32.0.1"))
        assertFalse(NetworkSafety.isCleartextPermittedHost("8.8.8.8"))
    }

    @Test
    fun redactsApiKeyFromUrls() {
        val redacted = NetworkSafety.redactUrl("http://192.168.1.10:9696/api?apikey=secret123&t=search")
        assertEquals("http://192.168.1.10:9696/api?apikey=[REDACTED]&t=search", redacted)
    }

    @Test(expected = PublicCleartextRejectedException::class)
    fun blocksPlainHttpToPublicDomain() = runTest {
        val client = HardenedNetworkClient(DohProvider.System)
        client.getText("http://example.com/test")
    }

    @Test
    fun allowsLocalhostHttpForLoopbackTorznab() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))
        server.start()
        try {
            val client = HardenedNetworkClient(DohProvider.System)
            val text = client.getText(server.url("/api").toString())
            assertEquals("{\"ok\":true}", text)
        } finally {
            server.shutdown()
        }
    }

    @Test(expected = PublicCleartextRejectedException::class)
    fun blocksRedirectToPlainHttpPublicHost() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "http://example.com/downgrade"),
        )
        server.start()
        try {
            val client = HardenedNetworkClient(DohProvider.System)
            client.getText(server.url("/start").toString())
        } finally {
            server.shutdown()
        }
    }

    @Test(expected = MobileDataBlockedException::class)
    fun blocksPublicRequestsWhenWifiOnlyGateRejectsMobileData() = runTest {
        val client = HardenedNetworkClient(
            initialDoh = DohProvider.System,
            initialNetworkGate = { false },
        )
        client.getText("https://example.com/test")
    }

    @Test
    fun stillAllowsLoopbackWhenWifiOnlyGateRejectsPublicNetwork() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"local\":true}"))
        server.start()
        try {
            val client = HardenedNetworkClient(
                initialDoh = DohProvider.System,
                initialNetworkGate = { false },
            )
            val text = client.getText(server.url("/local").toString())
            assertEquals("{\"local\":true}", text)
        } finally {
            server.shutdown()
        }
    }
}
