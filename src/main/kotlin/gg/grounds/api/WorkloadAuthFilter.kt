package gg.grounds.api

import gg.grounds.security.WorkloadAuthenticator
import jakarta.annotation.Priority
import jakarta.inject.Inject
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.SecurityContext
import jakarta.ws.rs.ext.Provider
import java.security.Principal
import org.jboss.logging.Logger

/**
 * Requires a valid workload token on every matchmaker endpoint, and hands the caller's identity to
 * the resources as the request's [SecurityContext].
 *
 * Establishing identity here rather than per resource means there is no endpoint that can be
 * reached by forgetting an annotation — the filter covers the path prefix, and a new resource is
 * covered the moment it is mapped.
 *
 * The prefix is deliberately narrow: `/q/health` and `/q/metrics` sit outside it, because the
 * satellite's Alloy scrapes the metrics endpoint on this very port with no token, and a 401 there
 * would take the queue dashboards down without touching a single match.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
class WorkloadAuthFilter @Inject constructor(private val authenticator: WorkloadAuthenticator) :
    ContainerRequestFilter {

    override fun filter(requestContext: ContainerRequestContext) {
        val path = requestContext.uriInfo.path
        if (!path.startsWith(GUARDED_PREFIX) && !path.startsWith("/$GUARDED_PREFIX")) return

        if (!authenticator.isEnabled) {
            // Local runs and tests, where no kubelet projects a token.
            requestContext.securityContext = identity("local-development")
            return
        }

        val header = requestContext.getHeaderString("Authorization")
        val token = header?.removePrefix("Bearer ")?.trim()
        if (header == null || !header.startsWith("Bearer ") || token.isNullOrEmpty()) {
            requestContext.abortWith(problem(401, "Authentication is required.", "unauthenticated"))
            return
        }

        val subject =
            try {
                authenticator.authenticate(token)
            } catch (error: WorkloadAuthenticator.VerificationUnavailableException) {
                // The key set could not be fetched, so this token was never judged. A 401 here
                // would tell a correctly-credentialled client to stop retrying.
                LOG.warn("Token verification unavailable", error)
                requestContext.abortWith(
                    problem(503, "Cannot verify credentials right now.", "verification_unavailable")
                )
                return
            }

        if (subject == null) {
            requestContext.abortWith(problem(401, "Credentials are not valid.", "unauthenticated"))
            return
        }
        requestContext.securityContext = identity(subject)
    }

    private fun identity(subject: String): SecurityContext =
        object : SecurityContext {
            override fun getUserPrincipal(): Principal = Principal { subject }

            override fun isUserInRole(role: String): Boolean = false

            override fun isSecure(): Boolean = true

            override fun getAuthenticationScheme(): String = "Bearer"
        }

    private fun problem(status: Int, detail: String, code: String): Response =
        Response.status(status)
            .type(PROBLEM_JSON)
            .entity(
                ProblemDetails(
                    title = if (status == 401) "Unauthenticated" else "Service unavailable",
                    status = status,
                    detail = detail,
                    code = code,
                )
            )
            .build()

    companion object {
        private val LOG = Logger.getLogger(WorkloadAuthFilter::class.java)

        /** Everything the matchmaker serves. Health and metrics live under `/q` and stay open. */
        internal const val GUARDED_PREFIX = "v1/match"
    }
}
