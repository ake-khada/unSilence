package com.unsilence.app.data.auth

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** Minimal SharedPreferences stub — all reads return null/false/0. */
    private class StubSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()
        override fun getString(key: String?, defValue: String?) = data[key] as? String ?: defValue
        override fun contains(key: String?) = data.containsKey(key)
        override fun getAll(): Map<String, *> = data.toMap()
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
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
