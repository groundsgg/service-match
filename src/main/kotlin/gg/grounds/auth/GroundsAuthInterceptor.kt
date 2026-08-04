package gg.grounds.auth

import gg.grounds.security.WorkloadAuthenticator
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.quarkus.grpc.GlobalInterceptor
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * The gRPC half of authentication: reads `authorization: Bearer <jwt>`, hands the token to
 * [WorkloadAuthenticator], and stashes the verified claims in a gRPC Context so service code can
 * look up the caller via `AuthContext.current()`.
 *
 * The verification itself — JWKS source, cluster-CA trust, audience — lives in the authenticator,
 * shared with [gg.grounds.api.WorkloadAuthFilter]. This class is only the protocol adapter, and it
 * goes away with the gRPC surface.
 *
 * Set `grounds.auth.enabled=false` for local dev where nothing projects a token; the interceptor is
 * then a no-op.
 */
@ApplicationScoped
@GlobalInterceptor
class GroundsAuthInterceptor @Inject constructor(private val authenticator: WorkloadAuthenticator) :
    ServerInterceptor {

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        if (!authenticator.isEnabled) {
            return next.startCall(call, headers)
        }

        val authHeader = headers.get(AUTHORIZATION)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return call.reject(
                Status.UNAUTHENTICATED.withDescription("missing or malformed Authorization header")
            )
        }

        val claims =
            try {
                authenticator.verify(authHeader.removePrefix("Bearer ").trim())
            } catch (error: WorkloadAuthenticator.VerificationUnavailableException) {
                // The key set could not be fetched, so this token was never judged either way.
                // UNAVAILABLE is retriable and UNAUTHENTICATED is not: answering "your
                // credentials are wrong" here would turn a moment without keys into an outage
                // for callers whose credentials are in fact fine.
                LOG.warn("Token verification unavailable", error)
                return call.reject(
                    Status.UNAVAILABLE.withDescription("cannot verify credentials right now")
                )
            } ?: return call.reject(Status.UNAUTHENTICATED.withDescription("invalid token"))

        val ctx = Context.current().withValue(AuthContext.KEY, AuthClaims.from(claims))
        return Contexts.interceptCall(ctx, call, headers, next)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <ReqT, RespT> ServerCall<ReqT, RespT>.reject(
        status: Status
    ): ServerCall.Listener<ReqT> {
        close(status, Metadata())
        return NOOP_LISTENER as ServerCall.Listener<ReqT>
    }

    companion object {
        private val LOG = Logger.getLogger(GroundsAuthInterceptor::class.java)

        internal val AUTHORIZATION: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

        private val NOOP_LISTENER = object : ServerCall.Listener<Any>() {}
    }
}
