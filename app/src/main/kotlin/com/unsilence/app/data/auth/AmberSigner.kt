package com.unsilence.app.data.auth

import android.content.Context
import android.content.Intent
import com.vitorpamplona.quartz.nip55AndroidSigner.api.CommandType
import com.vitorpamplona.quartz.nip55AndroidSigner.api.SignerResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.permission.Permission
import com.vitorpamplona.quartz.nip55AndroidSigner.client.ExternalSignerLogin
import com.vitorpamplona.quartz.nip55AndroidSigner.client.isExternalSignerInstalled

private const val AMBER_PACKAGE = "com.greenart7c3.nostrsigner"

/**
 * Thin wrapper around Quartz's NIP-55 foreground signer API.
 * Covers login, permission registration, and re-authorization flows.
 */
object AmberSigner {

    /**
     * Full set of permissions unSilence needs over its lifetime. Requested at login
     * so Amber shows them all on its consent screen — one tap grants everything.
     * Without this, individual operations silently fail when Amber's content provider
     * returns "no permission" and Quartz skips the activity-intent fallback on
     * permanent rejections (per NIP-55 spec).
     */
    private val FULL_PERMISSIONS: List<Permission> = listOf(
        Permission(CommandType.SIGN_EVENT),
        Permission(CommandType.NIP44_ENCRYPT),
        Permission(CommandType.NIP44_DECRYPT),
        Permission(CommandType.NIP04_ENCRYPT),
        Permission(CommandType.NIP04_DECRYPT),
        Permission(CommandType.DECRYPT_ZAP_EVENT),
    )

    /** True when at least one NIP-55-compatible signer (e.g. Amber) is installed. */
    fun isInstalled(context: Context): Boolean = isExternalSignerInstalled(context)

    /**
     * Returns an Intent that launches the installed signer app and requests the
     * user's public key with the full permission set.
     */
    fun createLoginIntent(): Intent =
        ExternalSignerLogin.createIntent(FULL_PERMISSIONS, "")

    /**
     * Re-authorize intent for an existing Amber connection. Asks Amber to update
     * its permission set for this app to include the full FULL_PERMISSIONS list.
     * Pubkey passed through Quartz's typed API so Amber pre-selects the account.
     */
    fun createReauthorizeIntent(currentUserPubkey: String): Intent =
        ExternalSignerLogin.createIntent(FULL_PERMISSIONS, AMBER_PACKAGE)
            .putExtra("current_user", currentUserPubkey)

    /**
     * Parses the Intent delivered to [onActivityResult] / the ActivityResult launcher callback.
     * Returns the hex public key on success, or null on any failure / rejection.
     */
    fun parseLoginResult(data: Intent?): String? {
        data ?: return null
        return when (val r = ExternalSignerLogin.parseResult(data)) {
            is SignerResult.RequestAddressed.Successful -> r.result.pubkey
            else -> null
        }
    }
}
