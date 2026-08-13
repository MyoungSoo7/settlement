package github.lms.lemuel.notification.adapter.`in`.web

import github.lms.lemuel.notification.domain.NotificationTemplate
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import javax.crypto.SecretKey

/**
 * The identities a connected client may receive notifications for. Derived
 * from a verified JWT and nothing else — never from a path/query parameter,
 * which is exactly how a push stream turns into an IDOR.
 */
data class SubscriberIdentity(
    val subject: String,
    val recipients: Set<String>,
)

/**
 * Verifies the platform JWT (HS256, same secret and library as shared-common's
 * `JwtUtil`) and maps its claims to the recipient keys the notification hub
 * routes on:
 *  - `sub` (email) — the REST/demo path addresses recipients by email
 *  - `uid` — canonical Outbox events address the seller by id
 *  - the ops mailbox, for ADMIN only — events whose payload carried no
 *    addressable field land there and would otherwise be invisible.
 *
 * Fail-closed: with no (or too weak) secret configured, nothing resolves. The
 * service must keep booting without a secret — the Kafka and REST paths do not
 * need one — but the stream must not serve data on trust.
 */
@Component
class JwtSubscriberIdentityResolver(
    @Value("\${app.security.jwt.secret:}") secret: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val key: SecretKey? = buildKey(secret)

    /** Whether a usable signing key is configured. */
    val configured: Boolean get() = key != null

    private fun buildKey(secret: String): SecretKey? {
        val bytes = secret.trim().toByteArray(StandardCharsets.UTF_8)
        return when {
            bytes.isEmpty() -> {
                LoggerFactory.getLogger(javaClass).warn(
                    "app.security.jwt.secret is not set — the notification push stream is disabled (503)",
                )
                null
            }

            bytes.size < MIN_SECRET_BYTES -> {
                LoggerFactory.getLogger(javaClass).error(
                    "app.security.jwt.secret is shorter than {} bytes — treating it as unset; the push stream stays disabled",
                    MIN_SECRET_BYTES,
                )
                null
            }

            else -> Keys.hmacShaKeyFor(bytes)
        }
    }

    /**
     * @return the verified identity, or null when the token is missing,
     *   malformed, expired, signed with another key, or carries no identity we
     *   can route on.
     */
    fun resolve(token: String?): SubscriberIdentity? {
        val activeKey = key ?: return null
        val raw = token?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val claims = try {
            Jwts.parser().verifyWith(activeKey).build().parseSignedClaims(raw).payload
        } catch (e: Exception) {
            // Signature, expiry and shape failures are all "not authenticated";
            // the reason stays in the log, never in the response.
            log.debug("rejected stream token: {}", e.toString())
            return null
        }

        val email = claims.subject?.trim().orEmpty()
        val uid = when (val claim = claims["uid"]) {
            is Number -> claim.toLong().toString()
            is String -> claim.trim().takeIf { it.isNotEmpty() }
            else -> null
        }
        if (email.isEmpty() && uid == null) {
            log.debug("rejected stream token: no addressable identity (sub/uid)")
            return null
        }

        val role = (claims["role"] as? String)?.trim()?.uppercase()?.removePrefix("ROLE_")
        val recipients = buildSet {
            if (email.isNotEmpty()) add(email)
            uid?.let { add(it) }
            if (role == ADMIN_ROLE) add(NotificationTemplate.OPS_FALLBACK_RECIPIENT)
        }
        return SubscriberIdentity(subject = email.ifEmpty { uid!! }, recipients = recipients)
    }

    companion object {
        /** HMAC-SHA256 minimum key length: 256 bit = 32 byte (as in shared-common). */
        private const val MIN_SECRET_BYTES = 32
        private const val ADMIN_ROLE = "ADMIN"
        private const val BEARER = "Bearer "

        /**
         * Extracts the raw token. `EventSource` cannot set request headers, so
         * the query parameter is the only way a browser can authenticate an SSE
         * connection; the header still wins when both are present and is what
         * non-browser clients should use (a token in a URL can end up in access
         * logs — see docs/sse.md).
         */
        fun tokenFrom(authorization: String?, tokenParam: String?): String? {
            val header = authorization?.trim().orEmpty()
            if (header.startsWith(BEARER, ignoreCase = true)) {
                return header.substring(BEARER.length).trim().takeIf { it.isNotEmpty() }
            }
            return tokenParam?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}
