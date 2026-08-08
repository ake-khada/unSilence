package com.unsilence.app.data.network

import com.unsilence.app.di.AppModule
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

class UntrustedHttpNetworkGuardTest {
    @Test
    fun `scheme policy requires https except for onion`() {
        assertTrue(isAllowedUntrustedHttpUrl("https://example.com/".toHttpUrl()))
        assertFalse(isAllowedUntrustedHttpUrl("http://example.com/".toHttpUrl()))
        assertTrue(isAllowedUntrustedHttpUrl("http://example.onion/".toHttpUrl()))
    }

    @Test
    fun `literal private destinations are rejected before connection`() {
        val rejected = listOf(
            "https://127.0.0.1/image.jpg",
            "https://169.254.169.254/latest/meta-data/",
            "https://10.0.0.1/image.jpg",
            "https://172.16.0.1/image.jpg",
            "https://192.168.1.1/image.jpg",
            "https://100.64.0.1/image.jpg",
            "https://[::1]/image.jpg",
            "https://[fe80::1]/image.jpg",
            "https://[fd00::1]/image.jpg",
            "https://localhost/image.jpg",
        )

        rejected.forEach { raw ->
            assertNull("expected $raw to be rejected", parseAllowedUntrustedHttpUrl(raw))
        }
        assertEquals(
            "https://8.8.8.8/image.jpg",
            parseAllowedUntrustedHttpUrl("https://8.8.8.8/image.jpg")?.toString(),
        )
    }

    @Test
    fun `image and media clients install guard only as a network interceptor`() {
        val clients = listOf(
            AppModule.provideImageClient(OkHttpClient()),
            AppModule.provideMediaClient(OkHttpClient()),
        )

        clients.forEach { client ->
            assertEquals(1, client.networkInterceptors.count { it === UntrustedHttpNetworkGuard })
            assertFalse(client.interceptors.any { it === UntrustedHttpNetworkGuard })
        }
    }

    @Test
    fun `private and special-use addresses are rejected`() {
        val rejected = listOf(
            "0.0.0.0",
            "127.0.0.1",
            "169.254.1.1",
            "10.0.0.1",
            "172.16.0.1",
            "192.168.1.1",
            "224.0.0.1",
            "100.64.0.1",
            "::1",
            "fe80::1",
            "fd00::1",
            "ff02::1",
        )
        rejected.forEach { raw ->
            assertFalse("expected $raw to be rejected", InetAddress.getByName(raw).isPublicInternetAddress())
        }
        assertTrue(InetAddress.getByName("8.8.8.8").isPublicInternetAddress())
        assertTrue(InetAddress.getByName("2606:4700:4700::1111").isPublicInternetAddress())
    }

    @Test
    fun `redirect to a private address is rejected on its own network hop`() {
        val firstHop = "https://public.example/start".toHttpUrl()
        val redirectHop = "https://redirect.example/final".toHttpUrl()

        requireAllowedUntrustedNetworkHop(firstHop, InetAddress.getByName("8.8.8.8"))
        assertThrows(IOException::class.java) {
            requireAllowedUntrustedNetworkHop(redirectHop, InetAddress.getByName("127.0.0.1"))
        }
    }

    @Test
    fun `clearnet validation fails closed without a connected address`() {
        assertThrows(IOException::class.java) {
            requireAllowedUntrustedNetworkHop("https://example.com/".toHttpUrl(), null)
        }
    }
}
