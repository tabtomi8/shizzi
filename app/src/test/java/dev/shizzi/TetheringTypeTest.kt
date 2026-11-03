package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TetheringTypeTest {

    @Test
    fun `classifies usb gadget interfaces as usb`() {
        listOf("rndis0", "ncm0", "usb0").forEach { name ->
            assertEquals(name, TetheringType.USB, tetheringTypeOf(name))
        }
    }

    @Test
    fun `classifies hotspot interfaces as wifi`() {
        listOf("wlan0", "wlan1", "ap0", "swlan0").forEach { name ->
            assertEquals(name, TetheringType.WIFI, tetheringTypeOf(name))
        }
    }

    @Test
    fun `restarts usb tethering that is already running`() {
        val running = DownstreamReading.Tethered(listOf("ncm0"))

        assertEquals(listOf(TetheringType.USB), typesToRestart(listOf(TetheringType.USB), running))
    }

    @Test
    fun `leaves idle usb alone but still restarts the hotspot`() {
        val types = listOf(TetheringType.WIFI, TetheringType.USB)

        assertEquals(listOf(TetheringType.WIFI), typesToRestart(types, DownstreamReading.None))
    }

    @Test
    fun `stops usb when its state cannot be read`() {
        assertEquals(
            listOf(TetheringType.USB),
            typesToRestart(listOf(TetheringType.USB), DownstreamReading.Unknown),
        )
    }

    @Test
    fun `cycles the interfaces of usb that is already up instead of switching its function`() {
        val running = DownstreamReading.Tethered(listOf("ncm0"))

        assertEquals(
            listOf("ncm0"),
            usbInterfacesToCycle(listOf(TetheringType.WIFI, TetheringType.USB), running),
        )
    }

    @Test
    fun `cycles every tethered usb interface`() {
        val running = DownstreamReading.Tethered(listOf("rndis0", "ncm0"))

        assertEquals(
            listOf("rndis0", "ncm0"),
            usbInterfacesToCycle(listOf(TetheringType.USB), running),
        )
    }

    @Test
    fun `cycles nothing when the session does not use usb`() {
        val running = DownstreamReading.Tethered(listOf("ncm0"))

        assertEquals(emptyList<String>(), usbInterfacesToCycle(listOf(TetheringType.WIFI), running))
    }

    @Test
    fun `cycles nothing when usb is down or cannot be read`() {
        val types = listOf(TetheringType.USB)

        assertEquals(emptyList<String>(), usbInterfacesToCycle(types, DownstreamReading.None))
        assertEquals(emptyList<String>(), usbInterfacesToCycle(types, DownstreamReading.Unknown))
    }

    @Test
    fun `bluetooth and ethernet tethering are neither`() {
        listOf("bt-pan", "eth0").forEach { name ->
            assertNull(name, tetheringTypeOf(name))
        }
    }
}
