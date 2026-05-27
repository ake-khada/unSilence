package com.unsilence.app.data.relay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit tests for [categorizeTransportFailure] — no actual connections.
 */
class TransportCategorizationTest {

    @Test
    fun `categorizes UnknownHostException as DNS_RESOLUTION`() {
        val t = java.net.UnknownHostException("Unable to resolve host \"nx.example\"")
        assertEquals(SkipReason.DNS_RESOLUTION, categorizeTransportFailure(t, response = null))
    }

    @Test
    fun `categorizes cleartext message as CLEARTEXT_BLOCKED`() {
        val t = java.io.IOException(
            "CLEARTEXT communication to relay.jb55.com not permitted by network security policy"
        )
        assertEquals(SkipReason.CLEARTEXT_BLOCKED, categorizeTransportFailure(t, response = null))
    }

    @Test
    fun `categorizes SSLException as SSL_ERROR`() {
        val t = javax.net.ssl.SSLException("Read error: ssl=0x7a3c: Failure in SSL library")
        assertEquals(SkipReason.SSL_ERROR, categorizeTransportFailure(t, response = null))
    }

    @Test
    fun `categorizes SocketTimeoutException as CONNECT_TIMEOUT`() {
        val t = java.net.SocketTimeoutException("failed to connect after 10000ms")
        assertEquals(SkipReason.CONNECT_TIMEOUT, categorizeTransportFailure(t, response = null))
    }

    @Test
    fun `categorizes ConnectException as CONNECT_TIMEOUT`() {
        val t = java.net.ConnectException("Connection refused")
        assertEquals(SkipReason.CONNECT_TIMEOUT, categorizeTransportFailure(t, response = null))
    }

    @Test
    fun `categorizes ProtocolException with 502 as HTTP_UPGRADE_5XX`() {
        val t = java.net.ProtocolException("Expected HTTP 101 response but was '502 Bad Gateway'")
        assertEquals(SkipReason.HTTP_UPGRADE_5XX, categorizeTransportFailure(t, response = null))
    }

    @Test
    fun `categorizes ProtocolException with 403 as HTTP_UPGRADE_4XX`() {
        val t = java.net.ProtocolException("Expected HTTP 101 response but was '403 Forbidden'")
        assertEquals(SkipReason.HTTP_UPGRADE_4XX, categorizeTransportFailure(t, response = null))
    }

    @Test
    fun `categorizes generic IOException as UNKNOWN_FAILURE`() {
        val t = java.io.IOException("Connection reset by peer")
        assertEquals(SkipReason.UNKNOWN_FAILURE, categorizeTransportFailure(t, response = null))
    }
}
