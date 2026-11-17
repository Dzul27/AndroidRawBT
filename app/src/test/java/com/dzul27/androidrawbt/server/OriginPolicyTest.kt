package com.dzul27.androidrawbt.server

import org.junit.Assert.assertEquals
import org.junit.Test

class OriginPolicyTest {
    private val listed = listOf("https://pos.example.com", " http://localhost:3000/ ")

    @Test
    fun allowAnyAcceptsEverythingWithWildcard() {
        val expected = OriginDecision.Allowed("*", varyOrigin = false)
        assertEquals(expected, OriginPolicy.evaluate("https://evil.example", true, emptyList()))
        assertEquals(expected, OriginPolicy.evaluate(null, true, listed))
        assertEquals(expected, OriginPolicy.evaluate("null", true, listed))
    }

    @Test
    fun missingOriginIsAlwaysAccepted() {
        val expected = OriginDecision.Allowed(null, varyOrigin = false)
        assertEquals(expected, OriginPolicy.evaluate(null, false, emptyList()))
        assertEquals(expected, OriginPolicy.evaluate("", false, listed))
        assertEquals(expected, OriginPolicy.evaluate("  ", false, listed))
    }

    @Test
    fun listedOriginIsEchoedWithVary() {
        assertEquals(
            OriginDecision.Allowed("https://pos.example.com", varyOrigin = true),
            OriginPolicy.evaluate("https://pos.example.com", false, listed),
        )
    }

    @Test
    fun matchingIgnoresCaseTrailingSlashAndWhitespace() {
        assertEquals(
            OriginDecision.Allowed("HTTPS://POS.EXAMPLE.COM", varyOrigin = true),
            OriginPolicy.evaluate("HTTPS://POS.EXAMPLE.COM", false, listed),
        )
        assertEquals(
            OriginDecision.Allowed("http://localhost:3000", varyOrigin = true),
            OriginPolicy.evaluate("http://localhost:3000", false, listed),
        )
    }

    @Test
    fun unlistedOriginIsRejected() {
        assertEquals(OriginDecision.Rejected, OriginPolicy.evaluate("https://evil.example", false, listed))
        assertEquals(OriginDecision.Rejected, OriginPolicy.evaluate("https://pos.example.com:8443", false, listed))
        assertEquals(OriginDecision.Rejected, OriginPolicy.evaluate("https://pos.example.com", false, emptyList()))
        assertEquals(OriginDecision.Rejected, OriginPolicy.evaluate("null", false, listed))
    }

    @Test
    fun fileOriginNullMustBeListedExplicitly() {
        assertEquals(
            OriginDecision.Allowed("null", varyOrigin = true),
            OriginPolicy.evaluate("null", false, listOf("null")),
        )
    }
}
