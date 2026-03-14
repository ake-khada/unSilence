package com.unsilence.app.data.auth

import android.content.ContentResolver
import android.content.Intent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val AMBER_PACKAGE = "com.greenart7c3.nostrsigner"

@Singleton
class SigningManager @Inject constructor(
    private val keyManager: KeyManager,
    private val contentResolver: ContentResolver,
) {
    @Volatile
    private var signer: NostrSigner? = null

    @Synchronized
    private fun getOrCreateSigner(): NostrSigner? {
        signer?.let { return it }

        val pubkey = keyManager.getPublicKeyHex() ?: return null

        val newSigner = if (keyManager.isAmberMode) {
            NostrSignerExternal(pubkey, AMBER_PACKAGE, contentResolver)
        } else {
            val privKeyHex = keyManager.getPrivateKeyHex() ?: return null
            NostrSignerInternal(KeyPair(privKey = privKeyHex.hexToByteArray()))
        }

        signer = newSigner
        return newSigner
    }

    suspend fun <T : Event> sign(template: EventTemplate<T>): T? {
        val s = getOrCreateSigner() ?: return null
        return if (s is NostrSignerExternal) {
            runCatching { s.sign(template) }.getOrNull()
        } else {
            withContext(Dispatchers.Default) {
                runCatching { s.sign(template) }.getOrNull()
            }
        }
    }

    fun registerLauncher(launcher: (Intent) -> Unit) {
        if (keyManager.isAmberMode) {
            val s = getOrCreateSigner()
            (s as? NostrSignerExternal)?.registerForegroundLauncher(launcher)
        }
    }

    fun unregisterLauncher(launcher: (Intent) -> Unit) {
        (signer as? NostrSignerExternal)?.unregisterForegroundLauncher(launcher)
    }

    fun onAmberResult(data: Intent) {
        (signer as? NostrSignerExternal)?.newResponse(data)
    }

    fun clear() {
        signer = null
    }
}
