package gg.grounds.auth

import com.nimbusds.jwt.JWTClaimsSet
import io.grpc.Context

/**
 * Per-call caller-identity, populated by [GroundsAuthInterceptor] after JWT verification. Service
 * code looks up the current caller via `AuthContext.current()`.
 *
 * Today this exposes the projected ServiceAccount-Token subject (`system:serviceaccount:<ns>:<sa>`)
 * which is the closest thing we have to a stable plugin identity. As per-plugin ServiceAccounts
 * land (out of scope for this PR), the subject will narrow from the `default` SA to a
 * `plugin-<name>` SA, which is what method-ACL decisions will eventually key on.
 */
data class AuthClaims(val subject: String, val audience: List<String>, val issuer: String?) {
    companion object {
        fun from(claims: JWTClaimsSet): AuthClaims =
            AuthClaims(
                subject = claims.subject ?: "",
                audience = claims.audience ?: emptyList(),
                issuer = claims.issuer,
            )
    }
}

object AuthContext {
    internal val KEY: Context.Key<AuthClaims> = Context.key("grounds-auth-claims")

    /** Returns the current caller's claims, or null when auth is disabled. */
    fun current(): AuthClaims? = KEY.get()
}
