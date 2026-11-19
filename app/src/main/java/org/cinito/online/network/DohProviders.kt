package org.cinito.online.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

enum class DohProvider(val displayName: String) {
    System("DNS del sistema"),
    Cloudflare("Cloudflare (1.1.1.1)"),
    Quad9("Quad9 (9.9.9.9)"),
    AdGuard("AdGuard Unfiltered"),
    ControlD("Control D (p0)"),
    Njalla("Njalla (Sin registros)"),
}

object DohProviders {
    fun build(provider: DohProvider, bootstrapClient: OkHttpClient): okhttp3.Dns = when (provider) {
        DohProvider.System -> okhttp3.Dns.SYSTEM
        DohProvider.Cloudflare -> DnsOverHttps.Builder().client(bootstrapClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1"),
                InetAddress.getByName("162.159.36.1"),
                InetAddress.getByName("162.159.46.1"),
            ).build()
        DohProvider.Quad9 -> DnsOverHttps.Builder().client(bootstrapClient)
            .url("https://dns.quad9.net/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("9.9.9.9"),
                InetAddress.getByName("149.112.112.112"),
            ).build()
        DohProvider.AdGuard -> DnsOverHttps.Builder().client(bootstrapClient)
            .url("https://dns-unfiltered.adguard.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("94.140.14.140"),
                InetAddress.getByName("94.140.14.141"),
            ).build()
        DohProvider.ControlD -> DnsOverHttps.Builder().client(bootstrapClient)
            .url("https://freedns.controld.com/p0".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("76.76.2.0"),
                InetAddress.getByName("76.76.10.0"),
            ).build()
        DohProvider.Njalla -> DnsOverHttps.Builder().client(bootstrapClient)
            .url("https://dns.njal.la/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("95.215.19.53"),
            ).build()
    }
}
