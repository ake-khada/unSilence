package com.barq.app.relay

import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit

object HttpClientFactory {

    private val TOR_TIMEOUT_MULTIPLIER = 3L

    /**
     * DNS resolver that forces all hostname resolution through the SOCKS5 proxy.
     * When Tor is active, returns an unresolved InetAddress so OkHttp sends the
     * hostname through the SOCKS tunnel and the Tor exit node resolves DNS.
     * This prevents DNS leaks.
     */
    private val torSafeDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
        }
    }

    // Shared across all relay WebSocket connections. OkHttp's default maxRequests=64
    // caps concurrent upgrade requests; 256 prevents queuing when outbox routing
    // opens 50+ ephemeral connections simultaneously.
    private val sharedRelayDispatcher = Dispatcher().apply {
        maxRequests = 256
        maxRequestsPerHost = 10
    }

    // Base relay client: shared dispatcher, ping interval, no read timeout, and the
    // Sec-WebSocket-Extensions interceptor that disables permessage-deflate.
    // createRelayClient() derives variants via .newBuilder() without rebuilding these.
    private val rootRelayClient = OkHttpClient.Builder()
        .dispatcher(sharedRelayDispatcher)
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            response.newBuilder()
                .removeHeader("Sec-WebSocket-Extensions")
                .build()
        }
        .build()

    fun createRelayClient(): OkHttpClient {
        val isTor = TorManager.isEnabled()
        if (!isTor) return rootRelayClient
        val builder = rootRelayClient.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .dns(torSafeDns)
        TorManager.proxy?.let { builder.proxy(it) }
        return builder.build()
    }

    private var cachedImageClient: OkHttpClient? = null
    private var cachedImageClientTorEnabled: Boolean? = null

    fun getImageClient(torEnabled: Boolean = TorManager.isEnabled()): OkHttpClient {
        if (cachedImageClient == null || cachedImageClientTorEnabled != torEnabled) {
            cachedImageClient = createHttpClient(
                connectTimeoutSeconds = 10,
                readTimeoutSeconds = 30
            )
            cachedImageClientTorEnabled = torEnabled
        }
        return cachedImageClient!!
    }

    fun createHttpClient(
        connectTimeoutSeconds: Long = 10,
        readTimeoutSeconds: Long = 10,
        writeTimeoutSeconds: Long = 0,
        followRedirects: Boolean = true
    ): OkHttpClient {
        val isTor = TorManager.isEnabled()
        val multiplier = if (isTor) TOR_TIMEOUT_MULTIPLIER else 1L

        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds * multiplier, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds * multiplier, TimeUnit.SECONDS)
            .followRedirects(followRedirects)

        if (writeTimeoutSeconds > 0) {
            builder.writeTimeout(writeTimeoutSeconds * multiplier, TimeUnit.SECONDS)
        }

        if (isTor) {
            TorManager.proxy?.let { builder.proxy(it) }
            builder.dns(torSafeDns)
        }

        return builder.build()
    }
}
