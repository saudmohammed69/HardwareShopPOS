package com.hardwareshop.pos.util

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Security layer.
 *
 * Password storage:
 *   PBKDF2WithHmacSHA256  —  iterations: 120_000 (NIST recommended 2024)
 *   Salt: 16 bytes random per password
 *   Output: 256-bit derived key
 *   Stored as Base64 in the database.
 *   Never store plain text.
 *
 * Role enforcement:
 *   Permissions are checked in the business layer, not just in the UI.
 *   Even if a worker somehow bypasses the UI, the repository/use-case
 *   layer re-validates the session role before performing any sensitive op.
 */
object SecurityManager {

    // ── Constants ────────────────────────────────────────────────────────────
    private const val ALGORITHM         = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS        = 120_000
    private const val KEY_LENGTH_BITS   = 256
    private const val SALT_LENGTH_BYTES = 16

    // ── Password hashing ─────────────────────────────────────────────────────

    data class HashedPassword(val hash: String, val salt: String)   // Base64 strings

    /** Hash a new password — call this when creating/changing a credential */
    suspend fun hashPassword(plainText: String): HashedPassword = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val salt = generateSalt()
        val hash = deriveKey(plainText, salt)
        HashedPassword(
            hash = Base64.encodeToString(hash, Base64.NO_WRAP),
            salt = Base64.encodeToString(salt, Base64.NO_WRAP)
        )
    }

    /**
     * Verify a plain-text password against a stored hash + salt.
     * Uses constant-time comparison to prevent timing attacks.
     */
    suspend fun verifyPassword(plainText: String, storedHash: String, storedSalt: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        try {
        val salt     = Base64.decode(storedSalt, Base64.NO_WRAP)
        val derived  = deriveKey(plainText, salt)
        val expected = Base64.decode(storedHash, Base64.NO_WRAP)
        constantTimeEquals(derived, expected)
        } catch (_: IllegalArgumentException) { false }
    }

    private fun generateSalt(): ByteArray =
        ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(
            password.toCharArray(),
            salt,
            ITERATIONS,
            KEY_LENGTH_BITS
        )
        return try { SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded } finally { spec.clearPassword() }
    }

    /** Constant-time byte array comparison — prevents timing side-channel attacks */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    // ── Input validation ─────────────────────────────────────────────────────


    fun validatePassword(password: String): ValidationResult {
        return when {
            password.length < 6     -> ValidationResult.Error("Password must be at least 6 characters")
            password.isBlank()      -> ValidationResult.Error("Password cannot be blank")
            else                    -> ValidationResult.OK
        }
    }

    sealed class ValidationResult {
        object OK : ValidationResult()
        data class Error(val message: String) : ValidationResult()
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// SESSION — in-memory only, never persisted to disk
// ═══════════════════════════════════════════════════════════════════════════

sealed class UserSession {
    object None : UserSession()
    data class WorkerSession(
        val workerId: Long,
        val workerName: String,
        val username: String,
        val maxDiscountPaisa: Long,
        val requireOwnerApprovalForDiscount: Boolean
    ) : UserSession()
    data class OwnerSession(
        val shopName: String
    ) : UserSession()
}

/**
 * SessionHolder — singleton kept in DI graph.
 * Backed by a volatile field; reset on logout.
 * Never written to disk or shared prefs — lives only in process memory.
 */
class SessionHolder {
    private val _session = kotlinx.coroutines.flow.MutableStateFlow<UserSession>(UserSession.None)

    val current: UserSession get() = _session.value
    val sessions: kotlinx.coroutines.flow.StateFlow<UserSession> get() = _session

    fun setSession(session: UserSession) { _session.value = session }

    fun clear() { _session.value = UserSession.None }

    val isOwner: Boolean     get() = _session.value is UserSession.OwnerSession
    val isLoggedIn: Boolean  get() = _session.value !is UserSession.None
}

// ═══════════════════════════════════════════════════════════════════════════
// PERMISSION CHECKER — business-layer enforcement (not UI-layer hiding)
// ═══════════════════════════════════════════════════════════════════════════

object PermissionChecker {

    sealed class CheckResult {
        object Granted : CheckResult()
        data class Denied(val reason: String) : CheckResult()
    }

    fun requireOwner(session: UserSession): CheckResult =
        if (session is UserSession.OwnerSession) CheckResult.Granted
        else CheckResult.Denied("This action requires owner authentication")

    fun requireWorkerOrOwner(session: UserSession): CheckResult =
        if (session is UserSession.OwnerSession || session is UserSession.WorkerSession)
            CheckResult.Granted
        else CheckResult.Denied("You must be logged in to perform this action")

    fun requireLogin(session: UserSession): CheckResult =
        requireWorkerOrOwner(session)

    /** Throws SecurityException if check fails — use inside repository/use-case */
    fun enforceOwner(session: UserSession) {
        if (requireOwner(session) is CheckResult.Denied)
            throw SecurityException("Owner authentication required for this operation")
    }

    fun enforceLogin(session: UserSession) {
        if (requireLogin(session) is CheckResult.Denied)
            throw SecurityException("Login required for this operation")
    }

    /**
     * Check whether the current worker session can apply a given discount
     * without owner approval.
     */
    fun workerCanApplyDiscount(session: UserSession, discountPaisa: Long): CheckResult =
        when (session) {
            is UserSession.OwnerSession  -> CheckResult.Granted
            is UserSession.WorkerSession -> {
                if (!session.requireOwnerApprovalForDiscount || discountPaisa <= session.maxDiscountPaisa) {
                    CheckResult.Granted
                } else {
                    CheckResult.Denied(
                        "Discount exceeds your limit of " +
                                CalculationEngine.formatRupees(session.maxDiscountPaisa) +
                                ". Owner approval required."
                    )
                }
            }
            else -> CheckResult.Denied("You must be logged in to apply a discount")
        }

    /**
     * Remote billing — enforced at repository layer, never UI-only.
     * WorkerSession is permanently denied regardless of UI state.
     */
    fun requireRemotePrint(session: UserSession): CheckResult =
        if (session is UserSession.OwnerSession) CheckResult.Granted
        else CheckResult.Denied("Remote printing is only available to the owner/brother")

    fun requireManageRemoteDevice(session: UserSession): CheckResult =
        if (session is UserSession.OwnerSession) CheckResult.Granted
        else CheckResult.Denied("Managing the shop print device requires owner access")

    fun enforceRemotePrint(session: UserSession) {
        if (requireRemotePrint(session) is CheckResult.Denied)
            throw SecurityException("Remote billing requires owner authentication")
    }

    fun enforceManageRemoteDevice(session: UserSession) {
        if (requireManageRemoteDevice(session) is CheckResult.Denied)
            throw SecurityException("Managing the remote print device requires owner authentication")
    }
}
