package com.unsilence.app.data.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.CommandType
import com.vitorpamplona.quartz.nip55AndroidSigner.api.SignerResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.permission.Permission
import com.vitorpamplona.quartz.nip55AndroidSigner.client.ExternalSignerLogin

/**
 * Thin wrapper around Quartz's NIP-55 foreground signer API.
 * Covers login, permission registration, and re-authorization flows.
 */
object AmberSigner {
    const val AMBER_PACKAGE = "com.greenart7c3.nostrsigner"

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

    fun isInstalled(context: Context): Boolean =
        createLoginIntent().resolveActivity(context.packageManager) != null

    /**
     * Returns an Intent that launches the installed signer app and requests the
     * user's public key with the full permission set.
     */
    fun createLoginIntent(): Intent =
        ExternalSignerLogin.createIntent(FULL_PERMISSIONS, AMBER_PACKAGE)

    /**
     * Re-authorize intent for an existing Amber connection. Asks Amber to update
     * its permission set for this app to include the full FULL_PERMISSIONS list.
     * Pubkey passed through Quartz's typed API so Amber pre-selects the account.
     */
    fun createReauthorizeIntent(currentUserPubkey: String): Intent =
        createLoginIntent().putExtra("current_user", currentUserPubkey)

    /**
     * Parses the Intent delivered to [onActivityResult] / the ActivityResult launcher callback.
     * Returns the hex public key on success, or null on any failure / rejection.
     */
    fun parseLoginResult(result: ActivityResult): String? {
        if (result.resultCode != Activity.RESULT_OK) return null
        val data = result.data ?: return null
        if (data.hasExtra("rejected")) return null
        val reply = ExternalSignerLogin.parseResult(data) as? SignerResult.RequestAddressed.Successful
        return reply?.result?.pubkey?.let(::normalizeAmberPubkey)
    }
}
