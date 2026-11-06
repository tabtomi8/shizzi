package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDownstreamTest {

    private class FakeUsbLink(
        var isConnected: Boolean? = false,
        var isFunctionOn: Boolean? = null,
    ) : UsbLink {
        var startCount = 0

        override fun isHostConnected(): Boolean? = isConnected

        override fun isTetheringFunctionOn(): Boolean? = isFunctionOn

        override fun startTethering(): Pair<Boolean, String> {
            startCount++
            return true to "started"
        }
    }

    @Test
    fun `always refuses to start without a cable`() {
        val usb = UsbDownstream(UsbTethering.ALWAYS, FakeUsbLink(isConnected = false))

        assertEquals(UsbDownstream.NO_USB_CABLE, usb.startProblem())
    }

    @Test
    fun `always starts when the cable state cannot be read`() {
        val usb = UsbDownstream(UsbTethering.ALWAYS, FakeUsbLink(isConnected = null))

        assertNull(usb.startProblem())
    }

    @Test
    fun `auto and never start without a cable`() {
        listOf(UsbTethering.AUTO, UsbTethering.NEVER).forEach { setting ->
            assertNull(setting.name, UsbDownstream(setting, FakeUsbLink()).startProblem())
        }
    }

    @Test
    fun `auto starts only the hotspot without a cable`() {
        val usb = UsbDownstream(UsbTethering.AUTO, FakeUsbLink(isConnected = false))

        assertEquals(listOf(TetheringType.WIFI), usb.typesToStart())
    }

    @Test
    fun `auto starts both with a cable`() {
        val usb = UsbDownstream(UsbTethering.AUTO, FakeUsbLink(isConnected = true))

        assertEquals(listOf(TetheringType.WIFI, TetheringType.USB), usb.typesToStart())
    }

    @Test
    fun `always awaits a pulled cable before giving up`() {
        val usb = UsbDownstream(UsbTethering.ALWAYS, FakeUsbLink())

        assertEquals(DownstreamHealth.Awaiting, usb.check(0, DownstreamReading.None))
        assertTrue(usb.isWaitingForUsb)
        assertTrue(usb.check(UsbReconnect.GIVE_UP_AFTER_MS, DownstreamReading.None) is DownstreamHealth.Ended)
    }

    @Test
    fun `auto never ends the session over a missing cable`() {
        val usb = UsbDownstream(UsbTethering.AUTO, FakeUsbLink())

        assertEquals(DownstreamHealth.Up, usb.check(0, DownstreamReading.None))
        assertEquals(DownstreamHealth.Up, usb.check(UsbReconnect.GIVE_UP_AFTER_MS, DownstreamReading.None))
        assertFalse(usb.isWaitingForUsb)
    }

    @Test
    fun `restarts usb tethering once a cable is connected`() {
        val link = FakeUsbLink()
        val usb = UsbDownstream(UsbTethering.AUTO, link)
        usb.check(0, DownstreamReading.None)

        link.isConnected = true
        usb.check(5_000, DownstreamReading.None)

        assertEquals(1, link.startCount)
    }

    @Test
    fun `never leaves usb alone`() {
        val link = FakeUsbLink(isConnected = true)

        assertEquals(DownstreamHealth.Up, UsbDownstream(UsbTethering.NEVER, link).check(0, DownstreamReading.None))
        assertEquals(0, link.startCount)
    }

    @Test
    fun `an unreadable tethering state neither restarts nor gives up`() {
        val link = FakeUsbLink(isConnected = true)
        val usb = UsbDownstream(UsbTethering.ALWAYS, link)

        assertEquals(DownstreamHealth.Up, usb.check(UsbReconnect.GIVE_UP_AFTER_MS, DownstreamReading.Unknown))
        assertEquals(0, link.startCount)
    }

    @Test
    fun `does not read the cable while usb is tethered`() {
        val link = object : UsbLink {
            override fun isHostConnected(): Boolean? = error("read the cable while tethered")
            override fun startTethering(): Pair<Boolean, String> = error("restarted while tethered")
        }
        val usb = UsbDownstream(UsbTethering.ALWAYS, link)

        assertEquals(DownstreamHealth.Up, usb.check(0, DownstreamReading.Tethered(listOf("ncm0"))))
    }

    @Test
    fun `only always starts before usb is tethered`() {
        assertTrue(UsbDownstream(UsbTethering.ALWAYS, FakeUsbLink()).canStartUntethered)
        assertFalse(UsbDownstream(UsbTethering.AUTO, FakeUsbLink()).canStartUntethered)
        assertFalse(UsbDownstream(UsbTethering.NEVER, FakeUsbLink()).canStartUntethered)
    }

    @Test
    fun `always started before usb is up waits for it`() {
        val link = FakeUsbLink(isConnected = true)
        val usb = UsbDownstream(UsbTethering.ALWAYS, link)

        assertEquals(DownstreamHealth.Awaiting, usb.check(0, DownstreamReading.None))
        assertEquals(1, link.startCount)
    }

    @Test
    fun `leaves a usb function that is already set for android to serve`() {
        val link = FakeUsbLink(isConnected = true, isFunctionOn = true)
        val usb = UsbDownstream(UsbTethering.ALWAYS, link)

        assertEquals(DownstreamHealth.Awaiting, usb.check(0, DownstreamReading.None))
        assertEquals(DownstreamHealth.Awaiting, usb.check(30_000, DownstreamReading.None))
        assertEquals(0, link.startCount)
    }

    @Test
    fun `restarts usb tethering once a set function stays unserved past the grace period`() {
        val link = FakeUsbLink(isConnected = true, isFunctionOn = true)
        val usb = UsbDownstream(UsbTethering.ALWAYS, link)

        usb.check(0, DownstreamReading.None)
        usb.check(UsbDownstream.FUNCTION_PENDING_GRACE_MS, DownstreamReading.None)

        assertEquals(1, link.startCount)
    }

    @Test
    fun `starts the grace period over once usb was served`() {
        val link = FakeUsbLink(isConnected = true, isFunctionOn = true)
        val usb = UsbDownstream(UsbTethering.ALWAYS, link)

        usb.check(0, DownstreamReading.None)
        usb.check(10_000, DownstreamReading.Tethered(listOf("rndis0")))
        usb.check(UsbDownstream.FUNCTION_PENDING_GRACE_MS, DownstreamReading.None)

        assertEquals(0, link.startCount)
    }

    @Test
    fun `restarts usb tethering when the function cannot be read`() {
        val link = FakeUsbLink(isConnected = true, isFunctionOn = null)
        val usb = UsbDownstream(UsbTethering.ALWAYS, link)

        usb.check(0, DownstreamReading.None)

        assertEquals(1, link.startCount)
    }
}
