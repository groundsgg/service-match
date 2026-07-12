package gg.grounds.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.Resource
import com.nimbusds.jose.util.ResourceRetriever
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.quarkus.grpc.GlobalInterceptor
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.io.FileInputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Server-side gRPC interceptor that:
 * 1. Reads the `authorization: Bearer <jwt>` metadata header
 * 2. Verifies the JWT against the configured JWKS endpoint (the k8s API-server's `/openid/v1/jwks`
 *    by default)
 * 3. Enforces audience = `grounds-services`
 * 4. Stashes the verified claims in a gRPC Context so service code can look up the caller via
 *    `AuthContext.current()`
 * 5. Rejects unauthenticated / invalid-token calls with Status.UNAUTHENTICATED
 *
 * Closes the SDK→service auth loop: library-grpc-contracts-sdk attaches the projected
 * ServiceAccount JWT on every call; this is the matching verification.
 *
 * Configuration (application.properties):
 *
 *     grounds.auth.enabled=true
 *     grounds.auth.jwks-url=https://kubernetes.default.svc/openid/v1/jwks
 *     grounds.auth.expected-audience=grounds-services
 *
 * Set `grounds.auth.enabled=false` for local dev where the SDK isn't attaching a token (e.g.
 * `quarkusDev` with no projected volume). In that mode the interceptor is a no-op.
 *
 * JWKS fetch: the OVH-MKS `/openid/v1/jwks` endpoint needs cluster-CA trust + this pod's SA-Token
 * as a bearer + `Accept: application/jwk-set+json` — a plain HTTPS GET fails with PKIX / 403 / 406.
 * In-cluster (the CA bundle is present) we use [K8sJwksRetriever]; locally we fall back to default
 * TLS trust. Mirrors service-nats-authz's SaTokenValidator.
 */
@ApplicationScoped
@GlobalInterceptor
class GroundsAuthInterceptor
@Inject
constructor(
    @param:ConfigProperty(name = "grounds.auth.enabled", defaultValue = "true")
    private val enabled: Boolean,
    @param:ConfigProperty(name = "grounds.auth.jwks-url") private val jwksUrl: String,
    @param:ConfigProperty(
        name = "grounds.auth.expected-audience",
        defaultValue = "grounds-services",
    )
    private val expectedAudience: String,
    @param:ConfigProperty(
        name = "grounds.auth.k8s-ca-file",
        defaultValue = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt",
    )
    private val caFile: String,
    @param:ConfigProperty(
        name = "grounds.auth.k8s-token-file",
        defaultValue = "/var/run/secrets/kubernetes.io/serviceaccount/token",
    )
    private val tokenFile: String,
) : ServerInterceptor {

    @Volatile private var jwtProcessor: DefaultJWTProcessor<SecurityContext>? = null

    @PostConstruct
    fun init() {
        if (!enabled) {
            LOG.warn(
                "Grounds auth disabled — gRPC calls will be processed without JWT verification"
            )
            return
        }
        val jwkSource =
            if (Files.exists(Path.of(caFile))) {
                // In-cluster: trust the cluster CA and authenticate the fetch with our SA-Token.
                val ssl = clusterCaSslContext(caFile)
                val retriever = K8sJwksRetriever(tokenFile, ssl.socketFactory)
                JWKSourceBuilder.create<SecurityContext>(URI.create(jwksUrl).toURL(), retriever)
                    .build()
            } else {
                // Local/test: no projected SA volume — fall back to system trust, no bearer.
                LOG.warnf("cluster CA %s not found — using default TLS trust (local/test)", caFile)
                JWKSourceBuilder.create<SecurityContext>(URI.create(jwksUrl).toURL()).build()
            }
        jwtProcessor =
            DefaultJWTProcessor<SecurityContext>().apply {
                jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, jwkSource)
                // Audience claim is required + enforced. Issuer is left permissive because k8s
                // SA-token issuers differ between clusters; audience binds the token to this
                // service-class, which is the actual abuse surface.
                jwtClaimsSetVerifier =
                    DefaultJWTClaimsVerifier<SecurityContext>(
                        JWTClaimsSet.Builder().audience(expectedAudience).build(),
                        setOf("sub", "exp"),
                    )
            }
        LOG.infof("Grounds auth enabled (jwks=%s, audience=%s)", jwksUrl, expectedAudience)
    }

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        if (!enabled) {
            return next.startCall(call, headers)
        }

        val authHeader = headers.get(AUTHORIZATION)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            call.close(
                Status.UNAUTHENTICATED.withDescription("missing or malformed Authorization header"),
                Metadata(),
            )
            return NOOP_LISTENER as ServerCall.Listener<ReqT>
        }

        val token = authHeader.removePrefix("Bearer ").trim()
        val claims =
            try {
                jwtProcessor!!.process(token, null)
            } catch (e: Exception) {
                LOG.debugf("JWT verification failed: %s", e.message)
                call.close(
                    Status.UNAUTHENTICATED.withDescription("invalid token: ${e.message}"),
                    Metadata(),
                )
                return NOOP_LISTENER as ServerCall.Listener<ReqT>
            }

        val ctx = Context.current().withValue(AuthContext.KEY, AuthClaims.from(claims))
        return Contexts.interceptCall(ctx, call, headers, next)
    }

    /** Builds an [SSLContext] trusting only the cluster CA bundle at [caFile]. */
    private fun clusterCaSslContext(caFile: String): SSLContext {
        val certs =
            FileInputStream(caFile).use {
                CertificateFactory.getInstance("X.509").generateCertificates(it)
            }
        val ks =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                certs.forEachIndexed { i, c -> setCertificateEntry("k8s-ca-$i", c) }
            }
        val tmf =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(ks)
            }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }

    companion object {
        private val LOG = Logger.getLogger(GroundsAuthInterceptor::class.java)

        internal val AUTHORIZATION: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

        private val NOOP_LISTENER = object : ServerCall.Listener<Any>() {}
    }
}

/**
 * Nimbus [ResourceRetriever] for the k8s JWKS endpoint: trusts the cluster CA and sends this pod's
 * SA-Token as a bearer (re-read per fetch — bound SA-Tokens rotate). Nimbus caches the fetched key
 * set, so this runs on warmup and the rare cache refresh, not per request.
 */
private class K8sJwksRetriever(
    private val tokenFile: String,
    private val socketFactory: SSLSocketFactory,
) : ResourceRetriever {
    override fun retrieveResource(url: URL): Resource {
        val token = Files.readString(Path.of(tokenFile)).trim()
        val conn =
            (url.openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = socketFactory
                setRequestProperty("Authorization", "Bearer $token")
                // The k8s OIDC JWKS endpoint only serves application/jwk-set+json and 406s on
                // application/json. Nimbus parses the body as JSON regardless of content-type.
                setRequestProperty("Accept", "application/jwk-set+json")
                connectTimeout = 1500
                readTimeout = 1500
            }
        val code = conn.responseCode
        if (code != 200) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IOException("JWKS fetch HTTP $code: ${err.take(200)}")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        return Resource(body, conn.contentType)
    }
}
