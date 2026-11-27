package com.unsilence.app.data.auth

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AmberLoginTest {
    private val publicKey = "a5".repeat(32)

    private fun reply() = Intent().putExtra("result", publicKey)
        .putExtra("package", AmberSigner.AMBER_PACKAGE)

    @Test fun loginOpensAmberWithTheSamePermissionsAsReauthorization() {
        val login = AmberSigner.createLoginIntent()
        val reauthorize = AmberSigner.createReauthorizeIntent(publicKey)
        assertEquals(AmberSigner.AMBER_PACKAGE, login.`package`)
        assertTrue(!login.getStringExtra("permissions").isNullOrBlank())
        assertEquals(reauthorize.getStringExtra("permissions"), login.getStringExtra("permissions"))
        assertNull(login.getStringExtra("current_user"))
        assertEquals(publicKey, reauthorize.getStringExtra("current_user"))
    }

    @Test fun successfulAmberReplyReturnsThePublicKey() {
        assertEquals(publicKey, AmberSigner.parseLoginResult(ActivityResult(Activity.RESULT_OK, reply())))
    }

    @Test fun cancelledResultWithDataCannotCompleteLogin() {
        assertNull(AmberSigner.parseLoginResult(ActivityResult(Activity.RESULT_CANCELED, reply())))
    }

    @Test fun explicitlyRejectedReplyCannotCompleteLogin() {
        val data = reply().putExtra("rejected", true)
        assertNull(AmberSigner.parseLoginResult(ActivityResult(Activity.RESULT_OK, data)))
    }

    @Test fun malformedReplyCannotCompleteAuthorization() {
        val data = reply().putExtra("result", "invalid")
        assertNull(AmberSigner.parseLoginResult(ActivityResult(Activity.RESULT_OK, data)))
    }
}
