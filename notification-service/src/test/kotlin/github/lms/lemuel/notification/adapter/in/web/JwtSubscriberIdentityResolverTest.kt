package github.lms.lemuel.notification.adapter.`in`.web

import github.lms.lemuel.notification.domain.NotificationTemplate
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Date
import javax.crypto.SecretKey

/**
 * The push stream is the one endpoint in this service that hands a user other
 * users' data if it gets identity wrong — so identity comes from a verified
 * JWT and nothing else.
 */
class JwtSubscriberIdentityResolverTest {

    private val secret = "notification-service-test-secret-32bytes+"
    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray())
    private val resolver = JwtSubscriberIdentityResolver(secret)

    private fun token(
        email: String? = "seller@lemuel.co.kr",
        role: String? = "USER",
        uid: Long? = 42,
        ttlSeconds: Long = 60,
        signingKey: SecretKey = key,
    ): String {
        val now = System.currentTimeMillis()
        val builder = Jwts.builder()
            .issuedAt(Date(now))
            .expiration(Date(now + ttlSeconds * 1000))
        email?.let { builder.subject(it) }
        role?.let { builder.claim("role", it) }
        uid?.let { builder.claim("uid", it) }
        return builder.signWith(signingKey).compact()
    }

    @Test
    fun `a valid token yields the email and the user id as identities`() {
        val identity = resolver.resolve(token())

        assertEquals(setOf("seller@lemuel.co.kr", "42"), identity?.recipients)
        assertEquals("seller@lemuel.co.kr", identity?.subject)
    }

    @Test
    fun `an admin additionally receives the ops fallback mailbox`() {
        val identity = resolver.resolve(token(email = "ops@lemuel.co.kr", role = "ADMIN", uid = 1))

        assertTrue(
            NotificationTemplate.OPS_FALLBACK_RECIPIENT in identity!!.recipients,
            "admins must see events that no seller identity could be derived for",
        )
    }

    @Test
    fun `a non-admin never receives the ops fallback mailbox`() {
        val identity = resolver.resolve(token(role = "USER"))

        assertFalse(NotificationTemplate.OPS_FALLBACK_RECIPIENT in identity!!.recipients)
    }

    @Test
    fun `a token signed with another key is rejected`() {
        val foreign = Keys.hmacShaKeyFor("a-completely-different-secret-32bytes".toByteArray())

        assertNull(resolver.resolve(token(signingKey = foreign)))
    }

    @Test
    fun `an expired token is rejected`() {
        assertNull(resolver.resolve(token(ttlSeconds = -60)))
    }

    @Test
    fun `garbage and missing tokens are rejected`() {
        assertNull(resolver.resolve("not.a.jwt"))
        assertNull(resolver.resolve(null))
        assertNull(resolver.resolve("  "))
    }

    @Test
    fun `a token carrying no addressable identity is rejected`() {
        assertNull(resolver.resolve(token(email = null, uid = null)))
    }

    @Test
    fun `a resolver with no secret is fail-closed`() {
        val unconfigured = JwtSubscriberIdentityResolver("")

        assertFalse(unconfigured.configured)
        // Even a perfectly valid token resolves to nothing: with no key there
        // is no way to verify anything, and guessing is not an option.
        assertNull(unconfigured.resolve(token()))
    }

    @Test
    fun `a secret shorter than 32 bytes counts as unconfigured`() {
        val weak = JwtSubscriberIdentityResolver("too-short")

        assertFalse(weak.configured)
        assertNull(weak.resolve(token()))
    }

    @Test
    fun `the token is read from the Authorization header or the query param`() {
        val raw = token()

        assertEquals(raw, JwtSubscriberIdentityResolver.tokenFrom("Bearer $raw", null))
        // EventSource cannot set headers, hence the query-param fallback.
        assertEquals(raw, JwtSubscriberIdentityResolver.tokenFrom(null, raw))
        // Header wins when both are present.
        assertEquals(raw, JwtSubscriberIdentityResolver.tokenFrom("Bearer $raw", "other"))
        assertNull(JwtSubscriberIdentityResolver.tokenFrom(null, null))
        assertNull(JwtSubscriberIdentityResolver.tokenFrom("Basic abc", null))
    }
}
