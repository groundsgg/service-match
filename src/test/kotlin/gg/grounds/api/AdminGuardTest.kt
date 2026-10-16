package gg.grounds.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The admin check on its own: `@QuarkusTest` runs with authentication off, so no request there
 * carries a subject that could be refused.
 */
class AdminGuardTest {

    @Test
    fun `admin service accounts pass`() {
        assertTrue(AdminGuard.isAdminSubject("system:serviceaccount:platform:platform-admin"))
        assertTrue(AdminGuard.isAdminSubject("system:serviceaccount:ops:match-admin"))
        AdminGuard.requireAdmin(
            "system:serviceaccount:ops:match-admin",
            authEnabled = true,
            operation = "deleteMode",
        )
    }

    @Test
    fun `a game server's service account is refused with 403`() {
        val subject = "system:serviceaccount:games:survivalgames"
        assertFalse(AdminGuard.isAdminSubject(subject))
        // Suffix, not substring: an account merely named after the admin one is not it.
        assertFalse(AdminGuard.isAdminSubject("system:serviceaccount:games:match-admin-impostor"))

        val refused =
            assertThrows(AdminRequiredException::class.java) {
                AdminGuard.requireAdmin(subject, authEnabled = true, operation = "deleteMode")
            }
        assertEquals(403, AdminRequiredMapper().toResponse(refused).status)
    }

    @Test
    fun `a request with no subject is refused`() {
        assertThrows(AdminRequiredException::class.java) {
            AdminGuard.requireAdmin(null, authEnabled = true, operation = "deleteMode")
        }
    }

    @Test
    fun `with authentication off there is no identity to refuse`() {
        AdminGuard.requireAdmin("local-development", authEnabled = false, operation = "deleteMode")
    }
}
