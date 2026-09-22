package com.unsilence.app.data.wallet

import android.content.SharedPreferences
import android.content.ContextWrapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val KEY_PUBKEY = "wallet_pubkey"
private const val KEY_RELAY = "wallet_relay"
private const val KEY_SECRET = "wallet_secret"
private const val KEY_OWNER = "owner_pubkey"

@OptIn(ExperimentalCoroutinesApi::class)
class NwcManagerOwnerFenceTest {
    private val ownerA = "a".repeat(64)
    private val ownerB = "b".repeat(64)

    private fun manager(prefs: SharedPreferences): NwcManager {
        val manager = NwcManager(ContextWrapper(null), OkHttpClient())

        val prefsField = NwcManager::class.java.getDeclaredField("prefs\$delegate")
        prefsField.isAccessible = true
        prefsField.set(manager, lazy { prefs })
        return manager
    }

    private fun seedCredentials(prefs: SharedPreferences) {
        prefs.edit()
            .putString(KEY_PUBKEY, "c".repeat(64))
            .putString(KEY_RELAY, "wss://relay.getalby.com/v1")
            .putString(KEY_SECRET, "d".repeat(64))
            .apply()
    }

    @Test
    fun `owner change clears credentials and restamps`() {
        val prefs = FakeSharedPreferences()
        seedCredentials(prefs)
        val nwc = manager(prefs)
        nwc.resetIfOwnerChanged(ownerA)

        nwc.resetIfOwnerChanged(ownerB)

        assertFalse(nwc.isConfigured)
        assertNull(nwc.connection())
        assertEquals(ownerB, prefs.getString(KEY_OWNER, null))
    }

    @Test
    fun `same owner rebootstrap keeps credentials`() {
        val prefs = FakeSharedPreferences()
        seedCredentials(prefs)
        val nwc = manager(prefs)

        nwc.resetIfOwnerChanged(ownerA)
        nwc.resetIfOwnerChanged(ownerA)

        assertTrue(nwc.isConfigured)
        assertNotNull(nwc.connection())
    }

    @Test
    fun `missing stamp adopts existing credentials without clearing`() {
        val prefs = FakeSharedPreferences()
        seedCredentials(prefs)
        val nwc = manager(prefs)

        nwc.resetIfOwnerChanged(ownerA)

        assertTrue(nwc.isConfigured)
        assertEquals(ownerA, prefs.getString(KEY_OWNER, null))
    }

    @Test
    fun `clear wipes stamp too`() {
        val prefs = FakeSharedPreferences()
        seedCredentials(prefs)
        val nwc = manager(prefs)

        nwc.resetIfOwnerChanged(ownerA)
        nwc.clear()

        assertNull(prefs.getString(KEY_OWNER, null))
        assertFalse(nwc.isConfigured)
    }

    @Test
    fun `owner comparison is case-insensitive`() {
        val prefs = FakeSharedPreferences()
        seedCredentials(prefs)
        val nwc = manager(prefs)

        nwc.resetIfOwnerChanged(ownerA)
        nwc.resetIfOwnerChanged(ownerA.uppercase())

        assertTrue(nwc.isConfigured)
    }

    @Test
    fun `saved wallet is available to the first observer without refresh`() {
        val prefs = FakeSharedPreferences()
        seedCredentials(prefs)
        val nwc = manager(prefs)

        assertEquals(NwcWalletState(true, "relay.getalby.com"), nwc.walletState.value)
    }

    @Test
    fun `existing action and settings observers both see connect and disconnect without navigation`() = runTest {
        val nwc = manager(FakeSharedPreferences())
        val actions = mutableListOf<NwcWalletState>()
        val settings = mutableListOf<NwcWalletState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            nwc.walletState.collect { actions.add(it) }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            nwc.walletState.collect { settings.add(it) }
        }

        // This is the same parsed-persistence path used by save(uri) in settings.
        nwc.persistConnection(NwcConnection("c".repeat(64), "wss://wallet.example/v1", "d".repeat(64)))
        assertTrue(nwc.isConfigured)
        nwc.clear()

        val expected = listOf(
            NwcWalletState(false, null),
            NwcWalletState(true, "wallet.example"),
            NwcWalletState(false, null),
        )
        assertEquals(expected, actions)
        assertEquals(expected, settings)
        assertNull(nwc.connection())
    }

    @Test
    fun `connecting before first observer publishes current saved state`() {
        val nwc = manager(FakeSharedPreferences())

        nwc.persistConnection(NwcConnection("c".repeat(64), "wss://wallet.example/v1", "d".repeat(64)))

        assertEquals(NwcWalletState(true, "wallet.example"), nwc.walletState.value)
    }

    @Test
    fun `wallet replacement updates label without a disconnected intermediate state`() = runTest {
        val prefs = FakeSharedPreferences()
        seedCredentials(prefs)
        val nwc = manager(prefs)
        val states = mutableListOf<NwcWalletState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            nwc.walletState.collect { states.add(it) }
        }

        nwc.persistConnection(NwcConnection("e".repeat(64), "wss://replacement.example/v1", "f".repeat(64)))

        assertEquals(listOf(
            NwcWalletState(true, "relay.getalby.com"),
            NwcWalletState(true, "replacement.example"),
        ), states)
        assertEquals("e".repeat(64), nwc.connection()?.walletPubkey)
    }

    @Test
    fun `owner change immediately invalidates observed wallet configuration`() {
        val prefs = FakeSharedPreferences()
        seedCredentials(prefs)
        val nwc = manager(prefs)
        val state = nwc.walletState
        nwc.resetIfOwnerChanged(ownerA)
        assertTrue(state.value.isConfigured)

        nwc.resetIfOwnerChanged(ownerB)

        assertEquals(NwcWalletState(false, null), state.value)
        assertEquals(ownerB, prefs.getString(KEY_OWNER, null))
    }

    @Test
    fun `same owner and background socket retirement keep observed configuration`() {
        val prefs = FakeSharedPreferences()
        seedCredentials(prefs)
        val nwc = manager(prefs)
        val state = nwc.walletState

        nwc.resetIfOwnerChanged(ownerA)
        nwc.resetIfOwnerChanged(ownerA.uppercase())
        nwc.suspendForBackground()

        assertEquals(NwcWalletState(true, "relay.getalby.com"), state.value)
        assertNotNull(nwc.connection())
    }

    @Test
    fun `invalid connection attempt preserves observed and saved configuration`() {
        val prefs = FakeSharedPreferences()
        seedCredentials(prefs)
        val nwc = manager(prefs)
        val state = nwc.walletState
        val originalConnection = nwc.connection()

        assertFalse(nwc.save(""))

        assertEquals(NwcWalletState(true, "relay.getalby.com"), state.value)
        assertEquals(originalConnection, nwc.connection())
    }
}

private class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()

    override fun getString(key: String?, defValue: String?) = data[key] as? String ?: defValue
    override fun contains(key: String?) = data.containsKey(key)
    override fun getAll(): Map<String, *> = data.toMap()
    override fun getInt(key: String?, defValue: Int) = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = data[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = data[key] as? Boolean ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValue: MutableSet<String>?): MutableSet<String>? {
        return (data[key] as? MutableSet<String>) ?: defValue
    }
    override fun edit(): SharedPreferences.Editor = FakeEditor(data)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

private class FakeEditor(
    private val data: MutableMap<String, Any?>,
) : SharedPreferences.Editor {
    override fun putString(key: String?, value: String?) = apply { data[key!!] = value }
    override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { data[key!!] = values }
    override fun putInt(key: String?, value: Int) = apply { data[key!!] = value }
    override fun putLong(key: String?, value: Long) = apply { data[key!!] = value }
    override fun putFloat(key: String?, value: Float) = apply { data[key!!] = value }
    override fun putBoolean(key: String?, value: Boolean) = apply { data[key!!] = value }
    override fun remove(key: String?) = apply { data.remove(key) }
    override fun clear() = apply { data.clear() }
    override fun commit() = true
    override fun apply() {}
}
