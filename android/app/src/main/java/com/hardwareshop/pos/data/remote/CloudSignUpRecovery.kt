package com.hardwareshop.pos.data.remote

import kotlinx.coroutines.CancellationException

/** Authentication only; shop membership and local owner approval stay with the caller. */
internal object CloudSignUpRecovery {
    data class ExistingAccount(val anonymous: Boolean, val email: String?)

    fun isExpired(code: String?) = code in setOf(
        "ERROR_USER_TOKEN_EXPIRED", "ERROR_INVALID_USER_TOKEN", "ERROR_USER_NOT_FOUND")

    suspend fun <T> createOrResume(
        previous: ExistingAccount?, email: String, hasLinkedShop: Boolean,
        linkAnonymous: suspend () -> T, createAccount: suspend () -> T,
        signIn: suspend () -> T, signOut: () -> Unit, errorCode: (Exception) -> String?
    ): T {
        if (previous == null) {
            check(!hasLinkedShop) { "This phone is already linked to a shop. Choose Sign in with that shop's owner account." }
            return createAccount()
        }
        if (!previous.anonymous) {
            check(previous.email?.equals(email.trim(), ignoreCase = true) == true) {
                "A different cloud account is connected. Choose Sign in with the connected shop's owner account."
            }
            // Account creation may have succeeded before shop setup lost its connection.
            // Authenticate the supplied password instead of trying to create the same account again.
            return signIn()
        }
        return try {
            linkAnonymous()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isExpired(errorCode(e))) throw e
            // An expired guest identity cannot authorise an existing cloud shop.
            signOut()
            check(!hasLinkedShop) {
                "The saved cloud sign-in has expired. Choose Sign in with this shop's owner account. Your local bills and products are kept."
            }
            // Only an unlinked guest is replaced. Disabled accounts are never retried.
            createAccount()
        }
    }
}

internal object CloudConnectionError {
    fun message(authCode: String?, firestoreCode: String?, networkFailure: Boolean): String? = when {
        authCode == "ERROR_USER_DISABLED" -> "This cloud account is disabled. Contact the Firebase project administrator."
        CloudSignUpRecovery.isExpired(authCode) ->
            "The saved cloud sign-in has expired. Choose Sign in and enter your shop account email and password. Your local bills and products are kept."
        authCode in setOf("ERROR_EMAIL_ALREADY_IN_USE", "ERROR_CREDENTIAL_ALREADY_IN_USE") ->
            "An account already uses this email. Choose Already have an account? Sign in."
        authCode in setOf("ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_LOGIN_CREDENTIALS", "ERROR_WRONG_PASSWORD") ->
            "Could not sign in. Check your cloud account email and password."
        authCode == "ERROR_OPERATION_NOT_ALLOWED" ->
            "Email/password sign-in is not enabled for this Firebase project. Ask the project administrator to enable it."
        authCode == "ERROR_WEAK_PASSWORD" -> "Choose a stronger password for your cloud account."
        authCode == "ERROR_INVALID_EMAIL" -> "Enter a valid email address."
        authCode == "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Wait a few minutes before trying again."
        networkFailure || firestoreCode in setOf("UNAVAILABLE", "DEADLINE_EXCEEDED") ->
            "The cloud connection was interrupted. Try again when internet is available. If account creation already succeeded, choose Sign in."
        firestoreCode == "PERMISSION_DENIED" ->
            "Cloud access was denied. Ask the Firebase project administrator to check this account's shop membership and the account/sync rules."
        firestoreCode == "UNAUTHENTICATED" ->
            "Cloud sign-in could not be verified. Choose Sign in and enter your shop account email and password."
        else -> null
    }
}
