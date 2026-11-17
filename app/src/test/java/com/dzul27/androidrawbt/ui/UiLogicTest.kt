package com.dzul27.androidrawbt.ui

import com.dzul27.androidrawbt.bluetooth.PrinterDevice
import com.dzul27.androidrawbt.service.ServiceStatus
import com.dzul27.androidrawbt.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogicTest {
    @Test
    fun portAcceptsRange() {
        assertEquals(1024, parsePort("1024"))
        assertEquals(8080, parsePort(" 8080 "))
        assertEquals(65535, parsePort("65535"))
    }

    @Test
    fun portRejectsInvalid() {
        assertNull(parsePort(""))
        assertNull(parsePort("1023"))
        assertNull(parsePort("65536"))
        assertNull(parsePort("0"))
        assertNull(parsePort("80a0"))
        assertNull(parsePort("-8080"))
        assertNull(parsePort("123456"))
        assertNull(parsePort("٨٠٨٠"))
    }

    @Test
    fun originNormalizesLikeBrowserOrigin() {
        assertEquals("https://pos.example.com", normalizeOrigin("pos.example.com"))
        assertEquals("https://pos.example.com", normalizeOrigin("  HTTPS://POS.Example.com/app/index.html?x=1 "))
        assertEquals("http://localhost:3000", normalizeOrigin("http://localhost:3000/"))
        assertEquals("https://pos.example.com", normalizeOrigin("https://pos.example.com:443"))
        assertEquals("http://pos.example.com", normalizeOrigin("http://pos.example.com:80"))
        assertEquals("https://pos.example.com:8443", normalizeOrigin("pos.example.com:8443"))
        assertEquals("null", normalizeOrigin("NULL"))
    }

    @Test
    fun originRejectsUnusable() {
        assertNull(normalizeOrigin(null))
        assertNull(normalizeOrigin("   "))
        assertNull(normalizeOrigin("ftp://example.com"))
        assertNull(normalizeOrigin("https://"))
        assertNull(normalizeOrigin("https://user:pw@example.com"))
        assertNull(normalizeOrigin("exa mple.com"))
        assertNull(normalizeOrigin("https://example.com:99999"))
    }

    @Test
    fun addOriginDetectsDuplicatesAndInvalid() {
        val first = addOrigin(emptyList(), "POS.example.com") as OriginAdd.Added
        assertEquals(listOf("https://pos.example.com"), first.origins)
        assertEquals(OriginAdd.Duplicate, addOrigin(first.origins, "https://pos.example.com/"))
        assertEquals(OriginAdd.Invalid, addOrigin(first.origins, "ftp://x"))
        val second = addOrigin(first.origins, "http://localhost:3000") as OriginAdd.Added
        assertEquals(listOf("https://pos.example.com", "http://localhost:3000"), second.origins)
    }

    @Test
    fun healthDerivation() {
        assertEquals(ServiceHealth.Running(8080), deriveHealth(ServiceStatus.Running(8080), true, true))
        assertEquals(ServiceHealth.PermissionMissing, deriveHealth(ServiceStatus.Running(8080), true, false))
        assertEquals(ServiceHealth.Stopped, deriveHealth(ServiceStatus.Stopped, false, true))
        assertEquals(ServiceHealth.StoppedBySystem, deriveHealth(ServiceStatus.Stopped, true, true))
        assertEquals(
            ServiceHealth.Failed("in use", true),
            deriveHealth(ServiceStatus.Failed("in use", true), true, true),
        )
    }

    @Test
    fun printerReadiness() {
        val devices = listOf(PrinterDevice("RPP02N", "DC:0D:51:58:00:01", true))
        assertFalse(isPrinterReady(null, devices, true))
        assertFalse(isPrinterReady("  ", devices, false))
        assertTrue(isPrinterReady("dc:0d:51:58:00:01", devices, true))
        assertFalse(isPrinterReady("00:11:22:33:44:55", devices, true))
        // List not trustworthy (Bluetooth off / permission missing): a saved MAC suffices.
        assertTrue(isPrinterReady("00:11:22:33:44:55", emptyList(), false))
    }

    @Test
    fun autoStartConditions() {
        val s = Settings(printerMac = "AA:BB:CC:DD:EE:FF", startOnAppOpen = true)
        assertTrue(shouldAutoStart(s, ServiceStatus.Stopped, true))
        assertFalse(shouldAutoStart(s.copy(startOnAppOpen = false), ServiceStatus.Stopped, true))
        assertFalse(shouldAutoStart(s.copy(printerMac = null), ServiceStatus.Stopped, true))
        assertFalse(shouldAutoStart(s, ServiceStatus.Running(8080), true))
        assertFalse(shouldAutoStart(s, ServiceStatus.Failed("x", false), true))
        assertFalse(shouldAutoStart(s, ServiceStatus.Stopped, false))
    }
}
