package github.lms.lemuel.reconciliation.adapter.out.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `app.sources.*` binding defaults.
 *
 * The defaults are empty strings on purpose: an unconfigured live source must
 * fail loudly on the first call, not quietly point at a plausible-looking
 * default host and reconcile against nothing.
 */
class SourcePropertiesTest {

    @Test
    fun `defaults are empty so a missing configuration cannot masquerade as a working one`() {
        val props = SourceProperties()

        assertEquals("", props.settlementBaseUrl)
        assertEquals("", props.paymentBaseUrl)
        assertEquals("", props.internalApiKey)
    }

    @Test
    fun `values bind positionally to the two sides plus the shared secret`() {
        val props = SourceProperties(
            settlementBaseUrl = "http://settlement-service:8082",
            paymentBaseUrl = "http://order-service:8088",
            internalApiKey = "shared-secret",
        )

        assertEquals("http://settlement-service:8082", props.settlementBaseUrl)
        assertEquals("http://order-service:8088", props.paymentBaseUrl)
        assertEquals("shared-secret", props.internalApiKey)
    }

    @Test
    fun `it has value semantics`() {
        val base = SourceProperties("http://a", "http://b", "k")

        assertEquals(base, base.copy())
        assertEquals(base.hashCode(), base.copy().hashCode())
        assertNotEquals(base, base.copy(internalApiKey = "other"))
        // toString must not be relied on to hide the key — the assertion here is
        // only that the properties are printable for config diagnostics.
        assertTrue(base.toString().contains("http://a"))
    }

    @Test
    fun `the internal api key header name matches the platform convention`() {
        // order/settlement gate their /internal endpoints on exactly this header.
        assertEquals("X-Internal-Api-Key", SourceConfig.INTERNAL_API_KEY_HEADER)
    }
}
