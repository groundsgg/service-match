package gg.grounds.api

/** Raised when a non-admin workload calls an admin-only endpoint. */
class AdminRequiredException(message: String) : RuntimeException(message)

/**
 * Admin checks for the few endpoints any workload token must not reach.
 *
 * Admin subjects are matched by suffix: a ServiceAccount subject like
 * `system:serviceaccount:<ns>:platform-admin` or `system:serviceaccount:<ns>:match-admin` passes.
 * Ops creates these ServiceAccounts by hand; their existence is the grant. The same convention as
 * service-leaderboard's AuthGuard.
 */
object AdminGuard {

    private val ADMIN_SA_SUFFIXES = listOf(":platform-admin", ":match-admin")

    fun isAdminSubject(subject: String?): Boolean =
        subject != null && ADMIN_SA_SUFFIXES.any { subject.endsWith(it) }

    /**
     * Throws [AdminRequiredException] unless [subject] is an admin. With authentication disabled —
     * local runs and tests, where no caller can present a token — there is no identity to check,
     * and every caller passes, exactly as every caller passes the workload filter there.
     */
    fun requireAdmin(subject: String?, authEnabled: Boolean, operation: String) {
        if (!authEnabled) return
        if (!isAdminSubject(subject)) {
            throw AdminRequiredException(
                "$operation requires an admin service account (caller=${subject ?: "<none>"})"
            )
        }
    }
}
