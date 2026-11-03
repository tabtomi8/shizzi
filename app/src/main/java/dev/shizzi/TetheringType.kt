package dev.shizzi

/** TetheringManager.TETHERING_* constants for the downstreams Shizzi can bring up. */
enum class TetheringType(val code: Int, val label: String) {
    WIFI(0, "hotspot"),
    USB(1, "usb tethering"),
}

private val USB_INTERFACE_PATTERN = Regex("""^(rndis|ncm|usb)\d*$""")

private val UNSHARED_INTERFACE_PATTERN = Regex("""^(bt-pan|eth\d+)$""")

/**
 * Classifies a tethered interface. Null for Bluetooth and Ethernet tethering,
 * which Shizzi neither starts nor stops; hotspot names vary by OEM, so anything
 * else counts as the hotspot.
 */
fun tetheringTypeOf(interfaceName: String): TetheringType? = when {
    USB_INTERFACE_PATTERN.matches(interfaceName) -> TetheringType.USB
    UNSHARED_INTERFACE_PATTERN.matches(interfaceName) -> null
    else -> TetheringType.WIFI
}

/**
 * The downstreams to stop before starting [types]. Stopping USB tethering with
 * nothing running can reset the USB function, but starting it while it runs
 * fails as a duplicate request, so USB is stopped unless it is known to be down.
 */
fun typesToRestart(types: List<TetheringType>, usbReading: DownstreamReading): List<TetheringType> =
    types.filter { type -> type != TetheringType.USB || usbReading != DownstreamReading.None }

/**
 * The tethered USB interfaces to cycle in place of stopping and starting USB
 * tethering. Both switch the USB function, and on many devices that restarts
 * adbd, which takes a Shizuku started through adbd down with it, along with the
 * session. A USB downstream that is already up keeps its function and has its
 * interfaces cycled instead, which still makes tethering choose its upstream
 * afresh: it does so when the first downstream comes back.
 */
fun usbInterfacesToCycle(types: List<TetheringType>, usbReading: DownstreamReading): List<String> =
    when {
        TetheringType.USB !in types -> emptyList()
        usbReading is DownstreamReading.Tethered -> usbReading.names
        else -> emptyList()
    }
