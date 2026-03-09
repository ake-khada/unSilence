package com.unsilence.app.data.auth

import android.content.Context
import android.content.Intent
import com.vitorpamplona.quartz.nip55AndroidSigner.api.SignerResult
import com.vitorpamplona.quartz.nip55AndroidSigner.client.ExternalSignerLogin
import com.vitorpamplona.quartz.nip55AndroidSigner.client.isExternalSignerInstalled

/**
 * Thin wrapper around Quartz's NIP-55 foreground signer API.
 * Covers the "login / get pubkey" flow for v1.
 * Signing via Amber (for posts, reactions, etc.) is wired in a future iteration.
 */
object AmberSigner {

    /** True when at least one NIP-55-compatible signer (e.g. Amber) is installed. */
    fun isInstalled(context: Context): Boolean = isExternalSignerInstalled(context)

    /**
     * Returns an Intent that launches the installed signer app and requests the user's public key.
     * The caller is responsible for launching via [ActivityResultLauncher] and forwarding the
     * result to [parseLoginResult].
     */
    fun createLoginIntent(): Intent =
        ExternalSignerLogin.createIntent(emptyList(), "")

    /**
     * Parses the Intent delivered to [onActivityResult] / the ActivityResult launcher callback.
     * Returns the hex public key on success, or null on any failure / rejection.
     */
    fun parseLoginResult(data: Intent?): String? {
        data ?: return null
        return when (val result = ExternalSignerLogin.parseResult(data)) {
            is SignerResult.RequestAddressed.Successful -> result.result.pubkey
            else -> null
        }
    }
}
