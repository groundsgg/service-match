package gg.grounds.auth

import io.grpc.Status

/**
 * Method-ACL decisions live here. Hard-coded today because the v2.2 Service Architecture spec calls
 * for server-side hard-coded Method-ACL — central config or a Keycloak-role lookup is YAGNI until
 * we have more than a handful of restricted methods.
 *
 * Admin subjects are matched by suffix: a JWT `sub` like
 * `system:serviceaccount:<ns>:platform-admin` or `system:serviceaccount:<ns>:leaderboard-admin`
 * passes the admin check. Ops creates these SAs manually in the platform-admin namespace; their
 * existence is the grant.
 */
object AuthGuard {

    private val ADMIN_SA_SUFFIXES = listOf(":platform-admin", ":leaderboard-admin")

    /**
     * Returns true if the current caller's JWT `sub` matches any configured admin SA. False when:
     * - Auth is disabled (dev mode)
     * - No claims present (shouldn't happen post-interceptor)
     * - Subject doesn't match an admin pattern
     */
    fun isAdmin(): Boolean {
        val claims = AuthContext.current() ?: return false
        return ADMIN_SA_SUFFIXES.any { suffix -> claims.subject.endsWith(suffix) }
    }

    /**
     * Throw PERMISSION_DENIED if the current caller is not an admin. For service code to call at
     * the top of an admin-only handler.
     */
    fun requireAdmin(operation: String) {
        if (!isAdmin()) {
            val sub = AuthContext.current()?.subject ?: "<no-auth>"
            throw Status.PERMISSION_DENIED.withDescription(
                    "$operation requires admin (caller=$sub)"
                )
                .asRuntimeException()
        }
    }

    /** Visible for testing. */
    internal fun isAdminSubject(subject: String): Boolean =
        ADMIN_SA_SUFFIXES.any { subject.endsWith(it) }
}
