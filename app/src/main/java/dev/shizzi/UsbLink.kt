package dev.shizzi

import android.content.Context

/** The USB reads and requests a session makes, apart from the decisions made on them. */
interface UsbLink {

    /** Null when `dumpsys usb` could not be read. */
    fun isHostConnected(): Boolean?

    /**
     * True when the USB function is already RNDIS or NCM, which Android serves on its own once a
     * host configures it. Null when `dumpsys usb` could not be read.
     */
    fun isTetheringFunctionOn(): Boolean? = null

    fun startTethering(): Pair<Boolean, String>
}

class AndroidUsbLink(
    private val context: Context,
    private val started: StartedDownstreams,
) : UsbLink {

    private val inspector = UpstreamInspector()

    override fun isHostConnected(): Boolean? = readUsb(::parseUsbHostConnected)

    override fun isTetheringFunctionOn(): Boolean? = readUsb(::parseUsbTetheringFunction)

    private fun readUsb(parse: (String) -> Boolean?): Boolean? {
        val observation = inspector.observeUsb()
        if (observation.didTimeout) return null

        return parse(observation.rawOutput)
    }

    override fun startTethering(): Pair<Boolean, String> {
        val outcome = DownstreamControl(context).startTethering(TetheringType.USB)
        if (outcome.first) started.add(TetheringType.USB)
        return outcome
    }
}

private val DEVICE_CONNECTED_PATTERN = Regex("""^\s*connected=(true|false)\s*$""", RegexOption.MULTILINE)

private val CURRENT_FUNCTIONS_PATTERN = Regex("""current_functions=(\[[^\]]*]|\S+)""")

private val FUNCTION_SEPARATOR = Regex("""[\s,\[\]"]+""")

/** RNDIS and NCM; older gadget HALs have no name for NCM and print its bit. */
private val TETHERING_FUNCTIONS = setOf("rndis", "ncm", "0x400")

private fun deviceManagerBlock(output: String): String? {
    val start = output.indexOf("device_manager={")
    if (start < 0) return null

    val end = output.indexOf("host_manager={", start).takeIf { it > start } ?: output.length
    return output.substring(start, end)
}

/** Reads whether a USB host is attached from `dumpsys usb`'s device_manager block. */
fun parseUsbHostConnected(output: String): Boolean? {
    val block = deviceManagerBlock(output) ?: return null

    return DEVICE_CONNECTED_PATTERN.find(block)?.groupValues?.get(1)?.toBooleanStrictOrNull()
}

/**
 * Reads whether the current USB function is RNDIS or NCM from `dumpsys usb`'s device_manager
 * block. No functions at all are not printed, so a block without the line reads false.
 */
fun parseUsbTetheringFunction(output: String): Boolean? {
    val block = deviceManagerBlock(output) ?: return null
    val functions = CURRENT_FUNCTIONS_PATTERN.find(block)?.groupValues?.get(1) ?: return false

    return functions.split(FUNCTION_SEPARATOR)
        .any { it.lowercase() in TETHERING_FUNCTIONS }
}
