package com.hardwareshop.pos

import com.hardwareshop.pos.data.remote.CloudConnectionError
import com.hardwareshop.pos.data.remote.CloudSignUpRecovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CloudSignUpRecoveryTest {
    private class Failure(val code: String) : Exception(code)
    private class FakeAuth {
        val calls = mutableListOf<String>()
        var failure: Exception? = null
        suspend fun connect(previous: CloudSignUpRecovery.ExistingAccount?, linked: Boolean = false,
            email: String = "owner@example.com") = CloudSignUpRecovery.createOrResume(
            previous, email, linked,
            linkAnonymous = { calls += "link"; failure?.let { throw it }; "same-guest-uid" },
            createAccount = { calls += "create"; "new-email-uid" },
            signIn = { calls += "sign-in"; failure?.let { throw it }; "email-uid" },
            signOut = { calls += "sign-out" }, errorCode = { (it as? Failure)?.code })
    }
    private val guest = CloudSignUpRecovery.ExistingAccount(true, null)
    @Test fun validGuestUpgradesInPlaceIncludingLinkedShop() = runBlocking {
        val fake = FakeAuth()
        assertEquals("same-guest-uid", fake.connect(guest, linked = true))
        assertEquals(listOf("link"), fake.calls)
    }
    @Test fun expiredUnlinkedGuestRegistersFreshEmailOnce() = runBlocking {
        for (code in listOf("ERROR_USER_TOKEN_EXPIRED", "ERROR_INVALID_USER_TOKEN", "ERROR_USER_NOT_FOUND")) {
            val fake = FakeAuth().apply { failure = Failure(code) }
            assertEquals("new-email-uid", fake.connect(guest))
            assertEquals(listOf("link", "sign-out", "create"), fake.calls)
        }
    }
    @Test fun expiredLinkedGuestCannotReplaceShopIdentity() = runBlocking {
        val fake = FakeAuth().apply { failure = Failure("ERROR_USER_TOKEN_EXPIRED") }
        try { fake.connect(guest, linked = true); fail("Must preserve the linked shop") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("Choose Sign in")) }
        assertEquals(listOf("link", "sign-out"), fake.calls)
    }
    @Test fun missingAuthenticationCannotCreateOverExistingShop() = runBlocking {
        val fake = FakeAuth()
        try { fake.connect(null, linked = true); fail("Must sign in to the linked shop") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("already linked")) }
        assertTrue(fake.calls.isEmpty())
    }
    @Test fun disabledNetworkAndCollisionFailuresAreNeverFreshRegistrations() = runBlocking {
        for (code in listOf("ERROR_USER_DISABLED", "ERROR_NETWORK_REQUEST_FAILED", "ERROR_EMAIL_ALREADY_IN_USE")) {
            val fake = FakeAuth().apply { failure = Failure(code) }
            try { fake.connect(guest); fail("Must propagate $code") }
            catch (e: Failure) { assertEquals(code, e.code) }
            assertEquals(listOf("link"), fake.calls)
        }
    }
    @Test fun cancellationPropagatesWithoutSigningOutOrRegistering() = runBlocking {
        val fake = FakeAuth().apply { failure = CancellationException("cancel") }
        try { fake.connect(guest); fail("Must cancel") } catch (_: CancellationException) { }
        assertEquals(listOf("link"), fake.calls)
    }
    @Test fun interruptedSameEmailSetupResumesWithPasswordSignIn() = runBlocking {
        val fake = FakeAuth()
        assertEquals("email-uid", fake.connect(CloudSignUpRecovery.ExistingAccount(false, "OWNER@example.com")))
        assertEquals(listOf("sign-in"), fake.calls)
    }
    @Test fun differentConnectedEmailCannotBeOverwritten() = runBlocking {
        val fake = FakeAuth()
        try { fake.connect(CloudSignUpRecovery.ExistingAccount(false, "other@example.com")); fail("Must not switch accounts") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("different cloud account")) }
        assertTrue(fake.calls.isEmpty())
    }
    @Test fun newPhoneCreatesAccountWithoutAnonymousRoundTrip() = runBlocking {
        val fake = FakeAuth()
        assertEquals("new-email-uid", fake.connect(null))
        assertEquals(listOf("create"), fake.calls)
    }
    @Test fun expiredSignInIsExplainedWithoutMisleadingFirebaseSetupAdvice() {
        val message = CloudConnectionError.message("ERROR_USER_TOKEN_EXPIRED", null, false)!!
        assertTrue(message.contains("Choose Sign in"))
        assertFalse(message.contains("rules"))
        assertFalse(message.contains("enable", true))
    }
    @Test fun networkCollisionAndPermissionFailuresHaveDifferentNextSteps() {
        assertTrue(CloudConnectionError.message(null, null, true)!!.contains("interrupted"))
        assertTrue(CloudConnectionError.message("ERROR_EMAIL_ALREADY_IN_USE", null, false)!!.contains("Already have an account"))
        assertTrue(CloudConnectionError.message(null, "PERMISSION_DENIED", false)!!.contains("membership"))
        assertTrue(CloudConnectionError.message("ERROR_OPERATION_NOT_ALLOWED", null, false)!!.contains("enable it"))
        assertTrue(CloudConnectionError.message("ERROR_USER_DISABLED", null, false)!!.contains("disabled"))
    }
}
