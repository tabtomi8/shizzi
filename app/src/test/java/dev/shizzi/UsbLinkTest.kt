package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbLinkTest {

    @Test
    fun `reads the device connection, not the host or port one`() {
        val dump = """
            USB MANAGER STATE (dumpsys usb):
              device_manager={
                handler={
                  current_functions=ncm
                  connected=false
                  configured=false
                  host_connected=true
                }
              }
              host_manager={
              }
              port_manager={
                connected=true
              }
        """.trimIndent()

        assertEquals(false, parseUsbHostConnected(dump))
    }

    @Test
    fun `reports a connected host`() {
        val dump = "  device_manager={\n    connected=true\n    configured=true\n  }\n  host_manager={\n  }"

        assertEquals(true, parseUsbHostConnected(dump))
    }

    @Test
    fun `unknown when the device block is missing`() {
        assertNull(parseUsbHostConnected("Can't find service: usb"))
    }

    @Test
    fun `reads a tethering function`() {
        val rndis = "  device_manager={\n    handler={\n      current_functions=RNDIS\n" +
            "      current_functions_applied=true\n      connected=true\n"
        val ncm = "  device_manager={\n    handler={\n      current_functions=ncm\n"

        assertEquals(true, parseUsbTetheringFunction(rndis))
        assertEquals(true, parseUsbTetheringFunction(ncm))
    }

    @Test
    fun `reads a tethering function among several`() {
        val dump = """
            device_manager={
              handler={
                current_functions=[
                  MTP
                  RNDIS
                ]
                current_functions_applied=true
        """.trimIndent()

        assertEquals(true, parseUsbTetheringFunction(dump))
    }

    @Test
    fun `other functions are not tethering`() {
        val mtp = "  device_manager={\n    handler={\n      current_functions=MTP\n" +
            "      current_functions_applied=true\n      screen_unlocked_functions=RNDIS\n"

        assertEquals(false, parseUsbTetheringFunction(mtp))
    }

    @Test
    fun `no functions are not printed and read as none`() {
        val none = "  device_manager={\n    handler={\n      current_functions_applied=true\n" +
            "      connected=true\n    }\n  }\n  host_manager={\n    current_functions=RNDIS\n  }"

        assertEquals(false, parseUsbTetheringFunction(none))
    }

    @Test
    fun `function unknown when the device block is missing`() {
        assertNull(parseUsbTetheringFunction("Can't find service: usb"))
    }
}
