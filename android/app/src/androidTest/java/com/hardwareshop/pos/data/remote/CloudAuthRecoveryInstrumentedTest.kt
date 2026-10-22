package com.hardwareshop.pos.data.remote

import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Uses SDK exceptions and controlled auth actions; never creates a live account. */
class CloudAuthRecoveryInstrumentedTest {
    @Test fun revokedFirebaseGuestRecoversOnlyWithoutAnExistingShopLink() = runBlocking {
        for (linked in listOf(false, true)) {
            val calls = mutableListOf<String>()
            val failure = FirebaseAuthInvalidUserException("ERROR_USER_TOKEN_EXPIRED",
                "The user's credential is no longer valid. The user must sign in again.")
            try {
                val result = CloudSignUpRecovery.createOrResume(
                    CloudSignUpRecovery.ExistingAccount(true, null), "test@example.com", linked,
                    linkAnonymous = { calls += "link"; throw failure },
                    createAccount = { calls += "create"; "new-owner" },
                    signIn = { calls += "sign-in"; "existing-owner" },
                    signOut = { calls += "sign-out" },
                    errorCode = { (it as? FirebaseAuthException)?.errorCode })
                assertFalse(linked)
                assertEquals("new-owner", result)
            } catch (e: IllegalStateException) {
                assertTrue(linked)
                assertTrue(e.message!!.contains("local bills and products are kept"))
            }
            assertEquals(if (linked) listOf("link", "sign-out") else listOf("link", "sign-out", "create"), calls)
        }
    }

    @Test fun disabledFirebaseAccountDoesNotTriggerNewRegistration() = runBlocking {
        var creates = 0
        val failure = FirebaseAuthInvalidUserException("ERROR_USER_DISABLED", "User disabled")
        try {
            CloudSignUpRecovery.createOrResume(CloudSignUpRecovery.ExistingAccount(true, null), "test@example.com", false,
                linkAnonymous = { throw failure }, createAccount = { creates++; "new" },
                signIn = { "signed-in" }, signOut = {}, errorCode = { (it as? FirebaseAuthException)?.errorCode })
            fail("Disabled account must not register again")
        } catch (e: FirebaseAuthInvalidUserException) {
            assertEquals("ERROR_USER_DISABLED", e.errorCode)
        }
        assertEquals(0, creates)
    }
}
