package com.nikhil.sentinelx.desktop.core.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The desktop matcher must agree with the phone's on every case, because the
 * same login has to match the same site in the browser extension and in the
 * app's autofill service. These cases mirror the Android
 * `AutofillMatcherTest` one-for-one; if one side changes, both fail.
 */
class BridgeMatcherTest {

    @Test
    fun `domain core strips www subdomains and country tlds`() {
        assertEquals("netflix", BridgeMatcher.domainCore("www.netflix.com"))
        assertEquals("google", BridgeMatcher.domainCore("accounts.google.co.in"))
        assertEquals("hdfcbank", BridgeMatcher.domainCore("netbanking.hdfcbank.com"))
        assertEquals("netflix", BridgeMatcher.domainCore("https://www.netflix.com/in/login"))
        assertEquals("localhost", BridgeMatcher.domainCore("localhost:8080"))
    }

    @Test
    fun `site label matches its domain`() {
        assertTrue(BridgeMatcher.matches("Netflix", "www.netflix.com", ""))
        assertTrue(BridgeMatcher.matches("HDFC Bank", "netbanking.hdfcbank.com", ""))
    }

    @Test
    fun `gmail alias reaches the google domain`() {
        assertTrue(BridgeMatcher.matches("Gmail", "accounts.google.com", ""))
    }

    @Test
    fun `unrelated domain does not match`() {
        assertFalse(BridgeMatcher.matches("Netflix", "www.amazon.in", ""))
    }

    @Test
    fun `one letter labels never match`() {
        assertFalse(BridgeMatcher.matches("N", "www.netflix.com", ""))
    }

    @Test
    fun `capture suggests the domain core capitalised`() {
        assertEquals("Netflix", BridgeMatcher.suggestedSiteName("www.netflix.com", "", null))
        assertEquals("Hdfcbank", BridgeMatcher.suggestedSiteName("netbanking.hdfcbank.com", "", null))
    }
}
