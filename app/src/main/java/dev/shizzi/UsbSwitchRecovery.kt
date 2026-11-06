package dev.shizzi

import android.os.DeadObjectException
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Starting USB tethering switches the USB function. On many devices that restarts adbd, which
 * takes down everything it started, including a Shizuku that was started through ADB, and with
 * it the shell process that holds the session. The start call then dies under the app with a
 * [DeadObjectException].
 *
 * That is expected once, because USB tethering stays up afterwards, and the next start finds it
 * up and leaves the USB function alone: it cycles the USB interfaces (see [usbInterfacesToCycle]),
 * or on Android 16, where that is gone, keeps them serving and makes tethering pick its upstream
 * again (see [reselectThroughPrivateDns]). So a start that loses its shell process to the switch
 * waits for Shizuku to come back and starts again.
 */
internal const val SHIZUKU_LOST_TO_USB_SWITCH =
    "USB tethering restarted ADB, which stopped Shizuku. Start Shizuku again, then start sharing: " +
        "USB tethering is already on, so it won't stop Shizuku a second time."

/** True when [failure] is the shell process going away under a call. */
fun isShellDeath(failure: Throwable): Boolean =
    generateSequence(failure) { it.cause }.any { it is DeadObjectException }

/**
 * Runs [start], and when [mayLoseShizuku] and the shell process died under it, waits with
 * [awaitShizuku] for Shizuku to come back and runs [start] once more. The second attempt's
 * outcome is final. A Shizuku that stays away gives a report in the shape the shell process
 * uses for a failed start, so it reaches the user as it is written.
 */
suspend fun startThroughUsbSwitch(
    mayLoseShizuku: Boolean,
    start: suspend () -> String,
    awaitShizuku: suspend () -> Boolean,
    onWaiting: () -> Unit = {},
    didShellDie: (Throwable) -> Boolean = ::isShellDeath,
): String {
    try {
        return start()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        if (!mayLoseShizuku || !didShellDie(failure)) throw failure
    }

    onWaiting()
    if (!awaitShizuku()) return lostShizukuReport()
    return start()
}

private fun lostShizukuReport(): String = JSONObject()
    .put("state", "ERROR")
    .put("detail", SHIZUKU_LOST_TO_USB_SWITCH)
    .toString()
