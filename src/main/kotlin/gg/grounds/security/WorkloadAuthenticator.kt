package gg.grounds.security

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.KeySourceException
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.Resource
import com.nimbusds.jose.util.ResourceRetriever
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
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
 * Who is calling, established from the projected workload token the caller presents.
 *
 * Every caller is in-cluster — plugin-match on the proxies, the game servers, forge — and they all
 * authenticate the same way: the kubelet projects a ServiceAccount token with the
 * `grounds-services` audience into the pod and the client sends it as a bearer.
 *
 * The token is verified against the cluster's JWKS rather than with a `TokenReview` call, the same
 * way service-coins and service-player do it. TokenReview needs cluster-scoped RBAC that the chart
 * cannot grant; verifying a signature needs no permissions at all, and Nimbus caches the key set so
 * a request costs a signature check rather than a round trip to the API server.
 *
 * This is transport-agnostic on purpose. It answers the one question both entry points have — REST
 * via [gg.grounds.api.WorkloadAuthFilter], gRPC via [gg.grounds.auth.GroundsAuthInterceptor] — so
 * there is one Nimbus processor and one JWKS cache in the process rather than one per protocol, and
 * so retiring the gRPC surface removes an adapter rather than a second copy of the rules.
 */
@ApplicationScoped
class WorkloadAuthenticator(
    @param:ConfigProperty(name = "grounds.auth.enabled", defaultValue = "true")
    private val enabled: Boolean,
    @param:ConfigProperty(name = "grounds.auth.jwks-url") private val jwksUrl: String,
    @param:ConfigProperty(
        name = "grounds.auth.expected-audience",
        defaultValue = "grounds-services",
    )
    private val audience: String,
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
) {

    @Volatile private var processor: DefaultJWTProcessor<SecurityContext>? = null

    /**
     * False when authentication is switched off for a local run; every caller is then anonymous.
     */
    val isEnabled: Boolean
        get() = enabled

    @PostConstruct
    fun init() {
        if (!enabled) {
            LOG.warn("Workload authentication disabled — calls are processed without verification")
            return
        }
        val source =
            if (Files.exists(Path.of(caFile))) {
                // In-cluster: trust the cluster CA and authenticate the fetch with our own token.
                val ssl = clusterCaSslContext(caFile)
                JWKSourceBuilder.create<SecurityContext>(
                        URI.create(jwksUrl).toURL(),
                        K8sJwksRetriever(tokenFile, ssl.socketFactory),
                    )
                    .build()
            } else {
                // Local/test: no projected SA volume — fall back to system trust, no bearer.
                LOG.warnf("Cluster CA %s not found — using default TLS trust (local/test)", caFile)
                JWKSourceBuilder.create<SecurityContext>(URI.create(jwksUrl).toURL()).build()
            }

        processor =
            DefaultJWTProcessor<SecurityContext>().apply {
                jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, source)
                // Audience is required and enforced; the issuer is left permissive because the
                // ServiceAccount issuer differs per cluster. Audience is what binds a token to
                // this service class rather than to any other holder of a valid cluster token.
                jwtClaimsSetVerifier =
                    DefaultJWTClaimsVerifier<SecurityContext>(
                        JWTClaimsSet.Builder().audience(audience).build(),
                        setOf("sub", "exp"),
                    )
            }
        LOG.infof("Workload authentication enabled (jwks=%s, audience=%s)", jwksUrl, audience)
    }

    /**
     * Returns the caller's subject (`system:serviceaccount:<ns>:<sa>`), or null when the token is
     * not a valid credential for this service.
     *
     * Throws [VerificationUnavailableException] when the key set cannot be fetched. That is
     * deliberately distinct from "invalid": telling a correctly-credentialled caller its
     * credentials are wrong makes it stop retrying, which turns a moment without keys into an
     * outage.
     *
     * Note what is *not* checked: that the subject looks like a ServiceAccount. service-coins
     * rejects anything else, but this service has never done so, and adding the check here would
     * change who can call the existing gRPC surface as a side effect of adding a REST one. It
     * belongs in its own change, with the deployed callers confirmed first.
     */
    fun authenticate(token: String): String? = verify(token)?.subject

    /**
     * The verified claim set, for a caller that needs more than the subject. Same contract as
     * [authenticate]: null when the token is not valid, [VerificationUnavailableException] when it
     * could not be judged either way.
     */
    fun verify(token: String): JWTClaimsSet? {
        val current = processor ?: throw VerificationUnavailableException(null)

        return try {
            current.process(token, null)
        } catch (error: KeySourceException) {
            throw VerificationUnavailableException(error)
        } catch (error: Exception) {
            LOG.debugf("Token rejected: %s", error.message)
            null
        }
    }

    /** Trusts only the cluster CA bundle at [caFile]. */
    private fun clusterCaSslContext(caFile: String): SSLContext {
        val certificates =
            FileInputStream(caFile).use {
                CertificateFactory.getInstance("X.509").generateCertificates(it)
            }
        val keyStore =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                certificates.forEachIndexed { index, certificate ->
                    setCertificateEntry("k8s-ca-$index", certificate)
                }
            }
        val trustManagers =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore)
            }
        return SSLContext.getInstance("TLS").apply { init(null, trustManagers.trustManagers, null) }
    }

    class VerificationUnavailableException(cause: Throwable?) :
        RuntimeException("cannot verify credentials", cause)

    companion object {
        private val LOG = Logger.getLogger(WorkloadAuthenticator::class.java)
    }
}

/**
 * Fetches the cluster's JWKS: trusts the cluster CA and sends this pod's own token as a bearer,
 * re-read per fetch because bound tokens rotate. Nimbus caches the key set, so this runs on warmup
 * and the occasional refresh rather than per request.
 *
 * The endpoint needs all three of those — a plain HTTPS GET fails with PKIX, then 403, then 406.
 */
private class K8sJwksRetriever(
    private val tokenFile: String,
    private val socketFactory: SSLSocketFactory,
) : ResourceRetriever {

    override fun retrieveResource(url: URL): Resource {
        val token = Files.readString(Path.of(tokenFile)).trim()
        val connection =
            (url.openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = socketFactory
                setRequestProperty("Authorization", "Bearer $token")
                // The endpoint serves application/jwk-set+json and answers 406 to
                // application/json. Nimbus parses the body as JSON regardless of content type.
                setRequestProperty("Accept", "application/jwk-set+json")
                connectTimeout = 1500
                readTimeout = 1500
            }
        val code = connection.responseCode
        if (code != 200) {
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IOException("JWKS fetch HTTP $code: ${error.take(200)}")
        }
        return connection.inputStream.use {
            Resource(it.readBytes().decodeToString(), connection.contentType)
        }
    }
}
