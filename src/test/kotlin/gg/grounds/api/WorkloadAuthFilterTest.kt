package gg.grounds.api

import gg.grounds.security.WorkloadAuthenticator
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.SecurityContext
import jakarta.ws.rs.core.UriInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The filter on its own, without booting the application: `@QuarkusTest` runs with authentication
 * switched off, because a test JVM has no projected token, so this is the only place the guarded
 * paths are actually exercised.
 */
class WorkloadAuthFilterTest {

    private val authenticator: WorkloadAuthenticator = mock()

    @Test
    fun `a request without credentials is refused`() {
        val request = request("v1/match/tickets", authorization = null)

        filter().filter(request)

        assertEquals(401, abortedStatus(request))
        verify(authenticator, never()).authenticate(any())
    }

    @Test
    fun `an Authorization header that is not a bearer is refused`() {
        val request = request("v1/match/tickets", "Basic dXNlcjpwYXNz")

        filter().filter(request)

        assertEquals(401, abortedStatus(request))
        verify(authenticator, never()).authenticate(any())
    }

    @Test
    fun `a token the cluster does not recognise is refused`() {
        whenever(authenticator.authenticate("bad")).thenReturn(null)
        val request = request("v1/match/tickets", "Bearer bad")

        filter().filter(request)

        assertEquals(401, abortedStatus(request))
    }

    @Test
    fun `an unreachable control plane is our failure, not the caller's`() {
        whenever(authenticator.authenticate("good"))
            .thenThrow(WorkloadAuthenticator.VerificationUnavailableException(null))
        val request = request("v1/match/tickets", "Bearer good")

        filter().filter(request)

        // 503, not 401: a correctly-credentialled client told "your credentials are wrong"
        // stops retrying, and a moment without keys then looks like a permanent outage.
        assertEquals(503, abortedStatus(request))
    }

    @Test
    fun `a valid token becomes the caller identity`() {
        whenever(authenticator.authenticate("good"))
            .thenReturn("system:serviceaccount:grounds:plugin-match")
        val request = request("v1/match/tickets", "Bearer good")

        filter().filter(request)

        verify(request, never()).abortWith(any())
        val captor = argumentCaptor<SecurityContext>()
        verify(request).securityContext = captor.capture()
        assertEquals(
            "system:serviceaccount:grounds:plugin-match",
            captor.firstValue.userPrincipal.name,
        )
    }

    @Test
    fun `metrics and probes are left alone`() {
        // Alloy scrapes /q/metrics on this very port with no token, and the kubelet
        // probes /q/health. A 401 on either takes the queue dashboards — or the pod —
        // down without touching a single match.
        for (path in listOf("q/metrics", "q/health/ready", "q/openapi")) {
            val request = request(path, null)
            filter().filter(request)
            verify(request, never()).abortWith(any())
        }
    }

    @Test
    fun `with authentication disabled the caller is a local identity rather than nobody`() {
        val disabled: WorkloadAuthenticator = mock()
        whenever(disabled.isEnabled).thenReturn(false)
        val request = request("v1/match/tickets", null)

        WorkloadAuthFilter(disabled).filter(request)

        verify(request, never()).abortWith(any())
        verify(request).securityContext = any()
    }

    private fun filter(): WorkloadAuthFilter {
        whenever(authenticator.isEnabled).thenReturn(true)
        return WorkloadAuthFilter(authenticator)
    }

    private fun request(path: String, authorization: String?): ContainerRequestContext {
        val uriInfo: UriInfo = mock()
        whenever(uriInfo.path).thenReturn(path)
        val context: ContainerRequestContext = mock()
        whenever(context.uriInfo).thenReturn(uriInfo)
        whenever(context.getHeaderString("Authorization")).thenReturn(authorization)
        return context
    }

    private fun abortedStatus(request: ContainerRequestContext): Int {
        val captor = argumentCaptor<Response>()
        verify(request).abortWith(captor.capture())
        return captor.firstValue.status
    }
}
