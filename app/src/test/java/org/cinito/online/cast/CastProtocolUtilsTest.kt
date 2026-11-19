package org.cinito.online.cast

import org.cinito.online.network.NetworkSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class CastProtocolUtilsTest {
    @Test
    fun convertsSrtTimestampsToValidWebVtt() {
        val srt = """
            1
            00:01:02,340 --> 00:01:05,890
            Hola mundo
        """.trimIndent()
        val vtt = CastProtocolUtils.srtToWebVtt(srt)
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("00:01:02.340 --> 00:01:05.890"))
        assertTrue(vtt.contains("Hola mundo"))
    }

    @Test
    fun parsesHttpByteRangesSafely() {
        assertEquals(0L..999L, CastProtocolUtils.parseHttpRangeHeader("bytes=0-", 1000L))
        assertEquals(100L..499L, CastProtocolUtils.parseHttpRangeHeader("bytes=100-499", 1000L))
        assertEquals(800L..999L, CastProtocolUtils.parseHttpRangeHeader("bytes=-200", 1000L))
        assertEquals(500L..999L, CastProtocolUtils.parseHttpRangeHeader("bytes=500-5000", 1000L))
        assertNull(CastProtocolUtils.parseHttpRangeHeader("bytes=1000-1100", 1000L))
        assertNull(CastProtocolUtils.parseHttpRangeHeader("bytes=0-10,20-30", 1000L))
    }

    @Test
    fun roundTripsCastWireProtobufMessage() {
        val original = CastWireMessage(
            protocolVersion = 0,
            sourceId = "sender-0",
            destinationId = "receiver-0",
            namespace = "urn:x-cast:com.google.cast.receiver",
            payloadType = 0,
            payloadUtf8 = """{"type":"LAUNCH","appId":"CC1AD845"}""",
        )
        val framed = CastProtocolUtils.encodeCastMessage(original)
        // Skip 4-byte length prefix
        val payloadLen = ((framed[0].toInt() and 0xFF) shl 24) or
            ((framed[1].toInt() and 0xFF) shl 16) or
            ((framed[2].toInt() and 0xFF) shl 8) or
            (framed[3].toInt() and 0xFF)
        assertEquals(framed.size - 4, payloadLen)

        val decoded = CastProtocolUtils.decodeCastMessage(framed.copyOfRange(4, framed.size))
        assertEquals(original, decoded)
    }

    @Test
    fun parsesDlnaDeviceXmlAndBlocksExternalSsdpLocation() {
        val lanSsdp = """
            HTTP/1.1 200 OK
            ST: urn:schemas-upnp-org:device:MediaRenderer:1
            LOCATION: http://192.168.1.45:38400/description.xml
        """.trimIndent()
        assertEquals(
            "http://192.168.1.45:38400/description.xml",
            CastProtocolUtils.parseSsdpLocationUrl(lanSsdp),
        )

        val evilPublicSsdp = """
            HTTP/1.1 200 OK
            LOCATION: http://8.8.8.8:8080/ssrf.xml
        """.trimIndent()
        assertNull(CastProtocolUtils.parseSsdpLocationUrl(evilPublicSsdp))

        val xml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <friendlyName>LG webOS TV Sala</friendlyName>
                <modelName>OLED55C3</modelName>
                <UDN>uuid:abc-123</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>/AVTransport/control</controlURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <controlURL>/RenderingControl/control</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()

        val dev = CastProtocolUtils.parseUpnpDeviceDescription(xml, "http://192.168.1.45:38400/description.xml")
        assertNotNull(dev)
        assertEquals("LG webOS TV Sala", dev?.name)
        assertEquals("http://192.168.1.45:38400/AVTransport/control", dev?.avTransportControlUrl)
        assertEquals("http://192.168.1.45:38400/RenderingControl/control", dev?.renderingControlUrl)
    }

    @Test
    fun formatsAndEscapesDlnaMetadata() {
        assertEquals("01:05:09", CastProtocolUtils.formatDlnaTime(3909_000L))
        assertEquals(3909_000L, CastProtocolUtils.parseDlnaTimeToMs("01:05:09.250"))

        val didl = CastProtocolUtils.dlnaDidlMetadataXml(
            title = "Spider-Man & Friends <4K>",
            mediaUrl = "http://192.168.1.10:9999/tok/media.mkv",
            mimeType = "video/x-matroska",
            subtitleUrl = "http://192.168.1.10:9999/tok/sub/0.srt",
        )
        assertTrue(didl.contains("Spider-Man &amp; Friends &lt;4K&gt;"))
        assertTrue(didl.contains("http://192.168.1.10:9999/tok/sub/0.srt"))
        assertTrue(didl.contains("<pv:subtitleFileUri>"))
        assertTrue(didl.contains("<sec:CaptionInfoEx"))
        assertFalse(didl.contains("""protocolInfo="http-get:*:text/srt:*""""))
        assertEquals(1, Regex("<res ").findAll(didl).count())
    }

    @Test
    fun resolvesMediaExtensionsAndMergesLgCapabilitiesWithoutDuplicates() {
        assertEquals(".mkv", CastProtocolUtils.mediaExtensionFor("Coco.2017.1080p.mkv", "application/octet-stream"))
        assertEquals(".mkv", CastProtocolUtils.mediaExtensionFor("stream", "video/x-matroska"))
        assertEquals(".mp4", CastProtocolUtils.mediaExtensionFor("stream", "video/mp4"))
        assertEquals(".webm", CastProtocolUtils.mediaExtensionFor("clip.webm", "video/webm"))

        val lgWebOsXml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <friendlyName>[LG] webOS TV UA7500</friendlyName>
                <manufacturer>LG Electronics</manufacturer>
                <modelName>50UA7500ZUA</modelName>
                <UDN>uuid:lg-tv-001</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:lge-com:service:webos-second-screen:1</serviceType>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()

        val webOsDev = CastProtocolUtils.parseUpnpDeviceDescription(lgWebOsXml, "http://192.168.1.100:1234/dev.xml")
        assertNotNull(webOsDev)
        assertEquals(CastProtocol.WebOs, webOsDev?.protocol)
        assertEquals(CastVendor.LG, webOsDev?.vendor)
        assertEquals(3000, webOsDev?.ssapPort)

        val dlnaLgDev = CastDevice(
            id = "dlna:uuid:lg-dlna-001",
            name = "[LG] webOS TV UA7500",
            model = "50UA7500ZUA",
            host = "192.168.1.100",
            port = 1898,
            protocol = CastProtocol.Dlna,
            vendor = CastVendor.LG,
            avTransportControlUrl = "http://192.168.1.100:1898/AVTransport/control",
            renderingControlUrl = "http://192.168.1.100:1898/RenderingControl/control",
        )

        val merged = CastProtocolUtils.mergeDiscoveredDevice(listOf(dlnaLgDev), webOsDev!!)
        assertEquals(1, merged.size)
        val combined = merged.first()
        assertEquals(CastProtocol.WebOs, combined.protocol)
        assertEquals(CastVendor.LG, combined.vendor)
        assertEquals(3000, combined.ssapPort)
        assertEquals("http://192.168.1.100:1898/AVTransport/control", combined.avTransportControlUrl)
    }

    @Test
    fun parsesUpnpSoapFaultErrorDescription() {
        val fault = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <s:Fault>
                  <detail>
                    <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
                      <errorCode>714</errorCode>
                      <errorDescription>Illegal MIME-Type</errorDescription>
                    </UPnPError>
                  </detail>
                </s:Fault>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        assertEquals("Illegal MIME-Type (714)", CastProtocolUtils.parseUpnpFaultMessage(fault))
    }

    @Test
    fun blocksLoopbackSsrfAndCrossHostControlUrlsAndMalformedCastVarint() {
        assertNull(
            CastProtocolUtils.parseSsdpLocationUrl(
                "HTTP/1.1 200 OK\nLOCATION: http://127.0.0.1:8080/description.xml",
            ),
        )
        assertNull(
            CastProtocolUtils.parseSsdpLocationUrl(
                "HTTP/1.1 200 OK\nLOCATION: http://localhost:3000/dev.xml",
            ),
        )

        val crossHostXml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <friendlyName>Evil Router</friendlyName>
                <UDN>uuid:evil</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>http://127.0.0.1:9000/kill</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()
        assertNull(CastProtocolUtils.parseUpnpDeviceDescription(crossHostXml, "http://192.168.1.99:1898/dev.xml"))

        // Malformed wire message with negative/huge length field (field 6, wire type 2, varint = -1 signed) must not throw
        val malformedWire = byteArrayOf(
            0x32,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x01,
        )
        val decoded = CastProtocolUtils.decodeCastMessage(malformedWire)
        assertEquals("", decoded.sourceId)
    }

    @Test
    fun convertsSrtWithSingleDigitHourAndStripsAssTags() {
        val srt = """
            1
            1:02:03,450 --> 1:02:06,789
            {\an8}{\i1}Hola, mundo{\i0}
        """.trimIndent()
        val vtt = CastProtocolUtils.srtToWebVtt(srt)
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("01:02:03.450 --> 01:02:06.789"))
        assertTrue(vtt.contains("Hola, mundo"))
        assertFalse(vtt.contains("{\\an8}"))
        assertFalse(vtt.contains("{\\i1}"))
    }

    @Test
    fun convertsAssToWebVttAndBackToSrt() {
        val ass = """
            [Script Info]
            Title: Test
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.50,0:00:04.25,Default,,0,0,0,,{\pos(320,200)}Hola,\Nmundo\hfeliz
            Dialogue: 0,1:05:10.05,1:05:12.00,Default,,0,0,0,,Solo {\b1}negrita{\b0}
        """.trimIndent()

        val vtt = CastProtocolUtils.assToWebVtt(ass)
        assertTrue(vtt.startsWith("WEBVTT\n\n"))
        assertTrue(vtt.contains("00:00:01.500 --> 00:00:04.250\nHola,\nmundo feliz"))
        assertTrue(vtt.contains("01:05:10.050 --> 01:05:12.000\nSolo negrita"))

        val vttWithNotes = """
            WEBVTT
            Kind: captions

            NOTE ignored comment

            00:01.200 --> 00:04.500 align:start position:10%
            <v Speaker>Primer subtítulo</v>

            1:02:03.450 --> 1:02:06.000
            Segundo subtítulo
        """.trimIndent()

        val srt = CastProtocolUtils.vttToSrt(vttWithNotes)
        assertTrue(srt.contains("1\n00:00:01,200 --> 00:00:04,500\nPrimer subtítulo"))
        assertTrue(srt.contains("2\n01:02:03,450 --> 01:02:06,000\nSegundo subtítulo"))
        assertFalse(srt.contains("NOTE"))
        assertFalse(srt.contains("align:start"))
        assertFalse(srt.contains("<v Speaker>"))
    }

    @Test
    fun decodesSubtitleBytesAcrossUtf8Utf16BomAndWindows1252() {
        val sample = "¡Hola! Canción, ñandú — “prueba”"
        // UTF-8 with BOM
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sample.toByteArray(Charsets.UTF_8)
        assertEquals(sample, CastProtocolUtils.decodeSubtitleBytes(utf8Bom))

        // UTF-16LE with BOM
        val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + sample.toByteArray(Charsets.UTF_16LE)
        assertEquals(sample, CastProtocolUtils.decodeSubtitleBytes(utf16LeBom))

        // UTF-16BE with BOM
        val utf16BeBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + sample.toByteArray(Charsets.UTF_16BE)
        assertEquals(sample, CastProtocolUtils.decodeSubtitleBytes(utf16BeBom))

        // Plain UTF-8 without BOM
        assertEquals(sample, CastProtocolUtils.decodeSubtitleBytes(sample.toByteArray(Charsets.UTF_8)))

        // Windows-1252 fallback (invalid UTF-8 sequence like 0xE1 'á', 0xF1 'ñ', 0x93 '“')
        val win1252 = Charset.forName("windows-1252")
        val winBytes = sample.toByteArray(win1252)
        assertEquals(sample, CastProtocolUtils.decodeSubtitleBytes(winBytes))
    }

    @Test
    fun mergeDiscoveredDevicePreservesWebOsPortAndMergesRenamedLgTv() {
        val webOs = CastDevice(
            id = "webos:192.168.1.70",
            name = "LG webOS TV (192.168.1.70)",
            model = null,
            host = "192.168.1.70",
            port = 3001,
            protocol = CastProtocol.WebOs,
            vendor = CastVendor.LG,
            ssapPort = 3001,
        )
        val dlnaRenamed = CastDevice(
            id = "dlna:uuid:lg-1873",
            name = "TV Sala",
            model = "OLED65C3",
            host = "192.168.1.70",
            port = 1873,
            protocol = CastProtocol.Dlna,
            vendor = CastVendor.Generic,
            avTransportControlUrl = "http://192.168.1.70:1873/AVTransport/control",
            renderingControlUrl = "http://192.168.1.70:1873/RenderingControl/control",
        )

        val merged = CastProtocolUtils.mergeDiscoveredDevice(listOf(webOs), dlnaRenamed)
        assertEquals(1, merged.size)
        val combined = merged.first()
        assertEquals(CastProtocol.WebOs, combined.protocol)
        assertEquals(CastVendor.LG, combined.vendor)
        assertEquals(3001, combined.port)
        assertEquals(3001, combined.ssapPort)
        assertEquals("TV Sala", combined.name)
        assertEquals("http://192.168.1.70:1873/AVTransport/control", combined.avTransportControlUrl)

        // Reverse order: synthetic WebOs name must not overwrite real DLNA friendlyName
        val mergedReverse = CastProtocolUtils.mergeDiscoveredDevice(listOf(dlnaRenamed), webOs)
        assertEquals(1, mergedReverse.size)
        assertEquals(CastProtocol.WebOs, mergedReverse.first().protocol)
        assertEquals(3001, mergedReverse.first().port)
        assertEquals("TV Sala", mergedReverse.first().name)
    }

    @Test
    fun parsesLgRenamedFriendlyNameBadUrlBaseAndGoogleDial() {
        val lgRenamedXml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <URLBase>http://10.99.99.99:9999/</URLBase>
              <device>
                <friendlyName>Sala Principal</friendlyName>
                <manufacturer>LG</manufacturer>
                <modelName>65UT8000</modelName>
                <UDN>uuid:lg-renamed</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>/AVTransport/control</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()
        val lgDev = CastProtocolUtils.parseUpnpDeviceDescription(lgRenamedXml, "http://192.168.1.88:1873/desc.xml")
        assertNotNull(lgDev)
        assertEquals(CastVendor.LG, lgDev?.vendor)
        assertEquals(CastProtocol.WebOs, lgDev?.protocol)
        assertEquals("http://192.168.1.88:1873/AVTransport/control", lgDev?.avTransportControlUrl)

        val googleDialXml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <deviceType>urn:dial-multiscreen-org:device:dial:1</deviceType>
                <friendlyName>Chromecast Cuarto</friendlyName>
                <manufacturer>Google Inc.</manufacturer>
                <modelName>Chromecast</modelName>
                <UDN>uuid:gcast-dial-1</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:dial-multiscreen-org:service:dial:1</serviceType>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()
        val gcastDev = CastProtocolUtils.parseUpnpDeviceDescription(googleDialXml, "http://192.168.1.90:8008/ssdp/device-desc.xml")
        assertNotNull(gcastDev)
        assertEquals(CastProtocol.GoogleCast, gcastDev?.protocol)
        assertEquals(CastVendor.Google, gcastDev?.vendor)
        assertEquals(8009, gcastDev?.port)
        assertEquals("Chromecast Cuarto", gcastDev?.name)
    }

    @Test
    fun validatesIpv6LinkLocalAndUlaInNetworkSafety() {
        assertTrue(NetworkSafety.isPrivateLanHost("fe80::1"))
        assertTrue(NetworkSafety.isPrivateLanHost("[fe80::a1b2:c3d4%wlan0]"))
        assertTrue(NetworkSafety.isPrivateLanHost("fc00::1"))
        assertTrue(NetworkSafety.isPrivateLanHost("fd12:3456:789a::1"))
        assertFalse(NetworkSafety.isPrivateLanHost("::1"))
        assertFalse(NetworkSafety.isPrivateLanHost("0:0:0:0:0:0:0:1"))
        assertFalse(NetworkSafety.isPrivateLanHost("2001:4860:4860::8888"))
        assertFalse(NetworkSafety.isPrivateLanHost("::ffff:127.0.0.1"))
    }
}
