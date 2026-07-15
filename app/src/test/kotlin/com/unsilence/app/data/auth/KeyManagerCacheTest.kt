package com.unsilence.app.data.auth

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field

/**
 * Verifies the pubkey caching contract in [KeyManager].
 *
 * KeyManager depends on EncryptedSharedPreferences (Android Keystore), so full
 * crypto integration requires instrumented testing. These tests bypass the
 * constructor and inject a stub SharedPreferences to verify cache invalidation.
 */
class KeyManagerCacheTest {

    private val cacheField: Field = KeyManager::class.java
        .getDeclaredField("cachedPubKeyHex").apply { isAccessible = true }

    private fun createKeyManager(): KeyManager {
        // Allocate without constructor via Java's internal Unsafe
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val km = allocMethod.invoke(theUnsafe, KeyManager::class.java) as KeyManager

        // Inject stub prefs via the lazy delegate field
        val prefsField = KeyManager::class.java.getDeclaredField("prefs\$delegate")
        prefsField.isAccessible = true
        prefsField.set(km, lazy<SharedPreferences> { StubSharedPreferences() })
        return km
    }

    /** Reads back the (stub) SharedPreferences injected into [km] so tests can
     *  assert raw marker state without going through crypto-deriving getters. */
    @Suppress("UNCHECKED_CAST")
    private fun prefsOf(km: KeyManager): SharedPreferences {
        val f = KeyManager::class.java.getDeclaredField("prefs\$delegate").apply { isAccessible = true }
        return (f.get(km) as Lazy<SharedPreferences>).value
    }

    @Test
    fun `getPublicKeyHex returns cached value without derivation`() {
        val km = createKeyManager()
        cacheField.set(km, "abc123")
        assertEquals("abc123", km.getPublicKeyHex())
    }

    @Test
    fun `getPublicKeyHex returns null when no key stored and cache empty`() {
        val km = createKeyManager()
        assertNull(km.getPublicKeyHex())
        assertNull(cacheField.get(km))
    }

    @Test
    fun `savePrivateKey invalidates cache`() {
        val km = createKeyManager()
        cacheField.set(km, "should-be-cleared")
        km.savePrivateKey("a".repeat(64))
        assertNull("Cache must be invalidated after savePrivateKey", cacheField.get(km))
    }

    @Test
    fun `clear invalidates cache`() {
        val km = createKeyManager()
        cacheField.set(km, "should-be-cleared")
        km.clear()
        assertNull("Cache must be invalidated after clear", cacheField.get(km))
    }

    // ── Cross-mode marker hygiene ──────────────────────────────────────────
    // A prior session's signer markers must not survive a key save, or the
    // resolved identity (isAmberMode / getPublicKeyHex / getPrivateKeyHex) is
    // wrong. State is seeded via the real save* APIs (not raw prefs).
    //
    // These assert on the markers only (isAmberMode, getPrivateKeyHex, the raw
    // pub_hex pref) — NOT on secp256k1 pubkey derivation, which is a Quartz crypto
    // class compiled for a newer JDK than the JDK-17 unit-test runtime (documented
    // JDK-mismatch test debt; getPublicKeyHex() in internal mode would throw
    // UnsupportedClassVersionError here). The derivation/identity path is covered by
    // device regression. generateNewKey() is intentionally untested here for the
    // same reason — it calls KeyPair() (crypto) before its pref write, so it can't
    // run under JDK 17. It uses the identical .remove(KEY_PUB_HEX)/.remove(
    // KEY_SIGNER_TYPE) pattern proven by `savePrivateKey clears stale Amber markers`.

    @Test
    fun `savePrivateKey clears stale Amber markers`() {
        val km = createKeyManager()
        // Seed a prior Amber session (SIGNER_TYPE=AMBER + a stored pubkey).
        km.saveAmberLogin("b".repeat(64))
        assertTrue("precondition: seeded Amber state", km.isAmberMode)

        km.savePrivateKey("a".repeat(64))

        assertFalse("SIGNER_TYPE marker cleared → no longer Amber mode", km.isAmberMode)
        assertEquals("new private key stored", "a".repeat(64), km.getPrivateKeyHex())
        assertNull(
            "stale Amber pubkey must be removed (else internal mode could surface it)",
            prefsOf(km).getString("pub_hex", null),
        )
    }

    @Test
    fun `saveAmberLogin clears stale private key`() {
        val km = createKeyManager()
        // Seed a prior internal-key session.
        km.savePrivateKey("a".repeat(64))
        assertNotNull("precondition: seeded private key", km.getPrivateKeyHex())

        val amberPubHex = "c".repeat(64)
        km.saveAmberLogin(amberPubHex)

        assertNull("stale private key must be removed", km.getPrivateKeyHex())
        assertTrue("must be in Amber mode", km.isAmberMode)
        assertEquals("Amber pubkey returned directly (no derivation)", amberPubHex, km.getPublicKeyHex())
    }

    @Test
    fun `graph completion persists empty outcome until follows exist`() {
        val km = createKeyManager()

        km.completeGraphOnboarding(hasFollows = false)
        assertTrue(km.isGraphKnownEmpty())
        assertTrue(km.isGraphOnboardingCompleted())

        km.completeGraphOnboarding(hasFollows = true)
        assertFalse(km.isGraphKnownEmpty())
        assertTrue(km.isGraphOnboardingCompleted())
    }

    /** Minimal SharedPreferences stub — all reads return null/false/0. */
    private class StubSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()
        override fun getString(key: String?, defValue: String?) = data[key] as? String ?: defValue
        override fun contains(key: String?) = data.containsKey(key)
        override fun getAll(): Map<String, *> = data.toMap()
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = data[key] as? Boolean ?: defValue
        override fun getStringSet(key: String?, dv: MutableSet<String>?) = dv
        override fun edit(): SharedPreferences.Editor = StubEditor(data)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class StubEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
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
}
