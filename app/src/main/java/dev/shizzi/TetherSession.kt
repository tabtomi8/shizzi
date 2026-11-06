package dev.shizzi

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject

enum class SessionState { IDLE, STARTING, ACTIVE, ERROR }

class TetherSession(private val context: Context) {

    private val testNetworkApi = TestNetworkApi(context)
    private val inspector = UpstreamInspector()

    private var resources: SessionResources? = null
    private var state = SessionState.IDLE
    private var detail = "not started"
    private var interfaceName: String? = null

    private var activeSince: Long = 0
    private var vpnMode = VpnMode.AUTO

    @Volatile
    private var usbTethering = UsbTethering.NEVER

    private var watchdog: SessionWatchdog? = null
    private var events: TetheringEvents? = null
    private val teardown = SessionTeardown(context)
    private val downstream = DownstreamInspector()
    private val started = StartedDownstreams()
    private val usbLink = AndroidUsbLink(context, started)

    @Volatile
    private var usbDownstream = UsbDownstream(UsbTethering.NEVER, usbLink)

    private val vpn = VpnUpstream(context) { problem -> tearDownAfter(problem) }

    val isActive: Boolean get() = state == SessionState.ACTIVE

    fun start(mode: VpnMode = VpnMode.AUTO, usb: UsbTethering = UsbTethering.NEVER): String {
        if (isActive) return status()

        vpnMode = mode
        usbTethering = usb
        usbDownstream = UsbDownstream(usb, usbLink, SessionLog::info)
        state = SessionState.STARTING
        SessionLog.info(
            "session start requested (vpn mode ${mode.name.lowercase()}, " +
                "usb tethering ${usb.name.lowercase()})",
        )

        SessionLog.info(
            "device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                "android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}), " +
                "contract ${TetherService.CONTRACT_VERSION}",
        )

        val startProblem = usbDownstream.startProblem()
        if (startProblem != null) {
            SessionLog.warn("start refused: $startProblem")
            state = SessionState.ERROR
            detail = startProblem
            return status()
        }

        return runCatching { bringUp() }
            .getOrElse { failure ->
                Log.e(TAG, "start failed", failure)
                SessionLog.error(
                    "start failed: ${failure.javaClass.simpleName}: ${failure.message}",
                )
                stop()
                state = SessionState.ERROR
                detail = "${failure.javaClass.simpleName}: ${failure.message}"
                status()
            }
    }

    private fun bringUp(): String {
        val group = SessionResources(testNetworkApi, context.connectivityManager())
        resources = group

        val name = group.acquire(tunAddresses(), TEST_NETWORK_DNS_SERVERS, AVAILABILITY_TIMEOUT_MS)
        interfaceName = name
        SessionLog.info("tun up: $name (mtu $TUN_MTU, $TUN_ADDRESS, $TUN_ADDRESS_V6)")

        group.startDatapath(TUN_MTU)
        SessionLog.info("datapath attached to $name")
        followVpn(group)

        preferTestNetworks()
        val isTethered = restartDownstream(name)

        if (isTethered || !usbDownstream.canStartUntethered) {
            verifyUpstream(name)
            SessionLog.info("upstream verified: $name is sole upstream")
        }
        if (TetheringType.USB in usbDownstream.typesToStart()) {
            started.add(TetheringType.USB)
        }

        state = SessionState.ACTIVE
        activeSince = System.currentTimeMillis()
        detail = "tethered clients routing through $name"

        teardown.installShutdownHook(started)
        startWatchdog(name)
        return status()
    }

    private fun followVpn(group: SessionResources) {
        if (vpnMode == VpnMode.NEVER) {
            SessionLog.info("vpn mode never: datapath left unbound")
            return
        }

        check(vpnMode != VpnMode.ALWAYS || vpn.isVpnPresent()) {
            "followVpn: vpn mode always requires an active VPN, none is connected"
        }
        vpn.follow(group)
    }

    private fun startWatchdog(name: String) {
        val checkUsb = { tethering: UpstreamObservation ->
            val usbReading = readDownstream(tethering, listOf(TetheringType.USB))
            usbDownstream.check(System.currentTimeMillis(), usbReading)
        }
        val guard = SessionWatchdog(name, checkDownstream = checkUsb, onEnd = ::endFromWatchdog)
        watchdog = guard
        guard.start()

        if (usbTethering == UsbTethering.NEVER) return
        events = TetheringEvents(context, guard::wake).also(TetheringEvents::register)
    }

    private fun endFromWatchdog(end: WatchdogEnd) {
        when (end) {
            is WatchdogEnd.Failed -> tearDownAfter(end.problem)
            is WatchdogEnd.Finished -> stop()
        }
    }

    private fun tearDownAfter(problem: String) {
        stop()

        val teardownProblem = detail.takeIf { state == SessionState.ERROR }
        state = SessionState.ERROR
        detail = when (teardownProblem) {
            null -> problem
            else -> "$problem; teardown incomplete: $teardownProblem"
        }
    }

    private fun preferTestNetworks() {
        val api = TetheringPreferenceApi(context)
        runCatching { api.setPreferTestNetworks(false) }
            .onFailure { SessionLog.warn("could not clear the stale test-network preference: ${it.message}") }
        api.setPreferTestNetworks(true)
    }

    /** How a start brings USB up; see [planUsb]. */
    private sealed interface UsbStart {
        /** USB is not part of this start. */
        object NotRequested : UsbStart

        /** Stopped if it may be up, then started, which switches the USB function. */
        object Restart : UsbStart

        /** Untethered in place; tethered again once the other downstreams are back. */
        class Cycled(val interfaces: List<String>) : UsbStart

        /** Left serving on the old upstream; tethering is then made to pick again. */
        object Kept : UsbStart

        /** The function is already set, and Android serves it by itself once the host is ready. */
        object AwaitingServing : UsbStart
    }

    /** True once a started downstream is tethered. */
    private fun restartDownstream(name: String): Boolean {
        val control = DownstreamControl(context)
        val types = usbDownstream.typesToStart()
        val usbReading = downstream.findTetheredDownstream(listOf(TetheringType.USB))
        val usb = planUsb(control, types, usbReading)

        val toRestart = if (usb is UsbStart.Restart) types else types - TetheringType.USB
        control.stopTethering(typesToRestart(toRestart, usbReading))

        val outcomes = toRestart.associateWith(control::startTethering).toMutableMap()
        when (usb) {
            is UsbStart.Cycled -> outcomes[TetheringType.USB] = control.tetherInterfaces(usb.interfaces)
            UsbStart.Kept -> outcomes[TetheringType.USB] = true to "kept serving"
            UsbStart.AwaitingServing -> outcomes[TetheringType.USB] = true to "function already set"
            UsbStart.NotRequested, UsbStart.Restart -> Unit
        }

        val (succeeded, failed) = outcomes.entries.partition { (_, outcome) -> outcome.first }
        val keptUsbWithoutSwitch = usb is UsbStart.Kept || usb is UsbStart.AwaitingServing
        succeeded.forEach { (type, _) ->
            if (type != TetheringType.USB || !keptUsbWithoutSwitch) started.add(type)
        }

        val failures = failed.map { (type, outcome) -> "${type.label} did not start (${outcome.second})" }
        check(succeeded.isNotEmpty()) { "restartDownstream: ${failures.joinToString("; ")}" }

        failures.forEach(SessionLog::warn)
        val isTethered = awaitDownstreamTethered(succeeded.map { it.key })

        if (usb is UsbStart.Kept) reselectUpstream(name)
        return isTethered
    }

    /**
     * Every USB function switch can restart adbd, and Shizuku and this process with it, so USB is
     * only switched when nothing else will do:
     *
     * - USB that is already serving has its interfaces cycled, which still makes tethering pick
     *   its upstream afresh when the first downstream comes back. Android 16 removed that, and
     *   there USB is kept and tethering is made to pick again (see [reselectUpstream]).
     * - On Android 16 a function that is already RNDIS or NCM is not asked for again: Android
     *   serves it by itself once the host is ready, and asking again makes it restart the function.
     *   This is the usual state right after a start that lost Shizuku to the switch.
     */
    private fun planUsb(
        control: DownstreamControl,
        types: List<TetheringType>,
        usbReading: DownstreamReading,
    ): UsbStart {
        if (TetheringType.USB !in types) return UsbStart.NotRequested

        val interfaces = usbInterfacesToCycle(types, usbReading)
        if (interfaces.isNotEmpty()) {
            return if (untetherUsb(control, interfaces)) UsbStart.Cycled(interfaces) else UsbStart.Kept
        }

        val isServingPending = usbReading == DownstreamReading.None &&
            Build.VERSION.SDK_INT > ANDROID_15_SDK &&
            usbLink.isTetheringFunctionOn() == true &&
            usbLink.isHostConnected() != false
        if (!isServingPending) return UsbStart.Restart

        SessionLog.info("usb function already set; leaving it to android to serve instead of switching it again")
        return UsbStart.AwaitingServing
    }

    private fun untetherUsb(control: DownstreamControl, interfaces: List<String>): Boolean {
        val (didUntether, outcome) = control.untetherInterfaces(interfaces)
        when {
            didUntether -> SessionLog.info(
                "usb tethering already up; cycling ${interfaces.joinToString()} " +
                    "without switching the usb function",
            )

            else -> SessionLog.info(
                "usb tethering already up and cannot be cycled in place ($outcome); " +
                    "keeping it up",
            )
        }
        return didUntether
    }

    /**
     * Makes tethering move USB that kept serving onto [name] without switching the USB function,
     * by briefly changing Private DNS (see [reselectThroughPrivateDns]). Both the nudge and its
     * restore rewrite the current upstream's LinkProperties; if tethering only moves on the
     * restore, [verifyUpstream] afterwards still catches it.
     */
    private fun reselectUpstream(name: String) {
        val before = liveUpstreams(name)
        if (before.isNotEmpty() && before.all { it == name }) {
            SessionLog.info("tethering already uses $name")
            return
        }

        val didMove = runCatching {
            reselectThroughPrivateDns(
                settings = ShellGlobalSettings(),
                awaitMoved = { awaitOnlyUpstream(name) },
                onEvent = SessionLog::info,
                onRestoreFailed = SessionLog::error,
            )
        }.getOrElse { failure ->
            SessionLog.warn("could not make tethering re-pick its upstream: ${failure.message}")
            false
        }

        if (didMove) {
            SessionLog.info("tethering moved usb onto $name without switching the usb function")
        } else {
            SessionLog.warn(
                "tethering upstream did not move during the private dns nudge " +
                    "(was $before, now ${liveUpstreams(name)}); waiting for verifyUpstream",
            )
        }
    }

    private fun isOnlyUpstream(name: String): Boolean {
        val observed = liveUpstreams(name)
        return observed.isNotEmpty() && observed.all { it == name }
    }

    private fun awaitOnlyUpstream(name: String): Boolean {
        val deadline = System.currentTimeMillis() + RESELECT_SETTLE_MS
        while (System.currentTimeMillis() < deadline) {
            if (isOnlyUpstream(name)) return true
            Thread.sleep(RESELECT_POLL_MS)
        }
        return isOnlyUpstream(name)
    }

    private fun awaitDownstreamTethered(types: List<TetheringType>): Boolean {
        val deadline = System.currentTimeMillis() + DOWNSTREAM_SETTLE_MS
        val downstream = DownstreamInspector()

        while (System.currentTimeMillis() < deadline) {

            if (downstream.findTetheredDownstream(types) is DownstreamReading.Tethered) {
                SessionLog.info("downstream tethered")
                return true
            }
            Thread.sleep(DOWNSTREAM_POLL_MS)
        }

        val hint = if (TetheringType.USB in types) " (is a USB cable connected to a computer?)" else ""
        SessionLog.warn("downstream not tethered after ${DOWNSTREAM_SETTLE_MS}ms$hint; continuing")
        return false
    }

    private fun verifyUpstream(name: String) {
        val deadline = System.currentTimeMillis() + UPSTREAM_SETTLE_MS
        var observed = liveUpstreams(name)

        while (System.currentTimeMillis() < deadline) {
            if (observed.isNotEmpty() && observed.all { it == name }) return
            Thread.sleep(UPSTREAM_POLL_MS)
            observed = liveUpstreams(name)
        }
        error("verifyUpstream: expected only $name, tethering reports $observed")
    }

    private fun liveUpstreams(owned: String): List<String> =
        inspector.observe().liveInterfaceNames(owned)

    fun stop(): String {
        watchdog?.stop()
        watchdog = null
        events?.unregister()
        events = null
        vpn.stop()
        teardown.removeShutdownHook()

        val summary = if (activeSince == 0L) null else sessionSummary()

        val downstreamProblem = teardown.releaseDownstream(started.types)
        started.clear()

        teardown.releaseUpstreamSelection(interfaceName)

        val releaseProblems = resources?.release().orEmpty()
        resources = null

        interfaceName = null
        activeSince = 0
        state = if (downstreamProblem == null) SessionState.IDLE else SessionState.ERROR
        detail = downstreamProblem ?: "stopped"

        when (downstreamProblem) {
            null -> SessionLog.info("session stopped; downstream confirmed down")
            else -> SessionLog.error("teardown: $downstreamProblem")
        }
        if (releaseProblems.isNotEmpty()) {
            SessionLog.warn("${releaseProblems.size} resource(s) not released cleanly")
        }
        summary?.let(SessionLog::info)
        return status()
    }

    private fun sessionSummary(): String {
        val traffic = interfaceName?.let(InterfaceCounters::read) ?: Traffic()
        val elapsed = when (activeSince) {
            0L -> 0L
            else -> System.currentTimeMillis() - activeSince
        }

        return describeSession(elapsed, traffic)
    }


    fun status(): String = JSONObject().apply {
        put("state", state.name)
        put("detail", detail)
        put("isWaitingForUsb", isActive && usbDownstream.isWaitingForUsb)
        put("interface", interfaceName ?: JSONObject.NULL)

        put("isVpnBound", vpn.isBound)
        put("isVpnBypassed", isVpnBypassed())

        val traffic = interfaceName?.let(InterfaceCounters::read) ?: Traffic()
        put("bytesUp", traffic.up)
        put("bytesDown", traffic.down)
        put("clientCount", if (isActive) downstream.countDevices(usbTethering.types) else 0)
    }.toString()

    private fun isVpnBypassed(): Boolean {
        if (vpnMode != VpnMode.NEVER) return false
        if (!isActive) return false

        return vpn.isVpnPresent()
    }

    private fun tunAddresses() = listOf(
        buildLinkAddress(java.net.InetAddress.getByName(TUN_ADDRESS), TUN_PREFIX_LENGTH),
        buildLinkAddress(java.net.InetAddress.getByName(TUN_ADDRESS_V6), TUN_PREFIX_LENGTH_V6),
    )

    private companion object {
        const val TAG = "TetherSession"
        const val TUN_ADDRESS = "192.0.2.2"
        const val TUN_PREFIX_LENGTH = 24

        const val TUN_ADDRESS_V6 = "2001:db8::2"
        const val TUN_PREFIX_LENGTH_V6 = 64

        const val TUN_MTU = 1500
        const val AVAILABILITY_TIMEOUT_MS = 10_000
        const val UPSTREAM_SETTLE_MS = 8_000L
        const val UPSTREAM_POLL_MS = 500L

        const val DOWNSTREAM_SETTLE_MS = 10_000L
        const val DOWNSTREAM_POLL_MS = 500L

        const val RESELECT_SETTLE_MS = 5_000L
        const val RESELECT_POLL_MS = 250L

        /** Build.VERSION_CODES.VANILLA_ICE_CREAM; Android 16 is anything above it. */
        const val ANDROID_15_SDK = 35
    }
}
