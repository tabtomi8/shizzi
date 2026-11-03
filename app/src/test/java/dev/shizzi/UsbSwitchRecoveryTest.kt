package dev.shizzi

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class UsbSwitchRecoveryTest {

    private class ShellDied : RuntimeException("shell process died")

    /** Plays one prepared step per start attempt. */
    private class Attempts(private vararg val steps: () -> String) {
        var count = 0
            private set

        suspend fun next(): String = steps[count++]()
    }

    /** Runs a block that never really suspends, which keeps these tests off a coroutine runtime. */
    private fun <T> runSuspending(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        return checkNotNull(outcome) { "the block suspended" }.getOrThrow()
    }

    private fun startThrough(
        attempts: Attempts,
        mayLoseShizuku: Boolean = true,
        isShizukuBack: Boolean = true,
        onWaiting: () -> Unit = {},
    ): String = runSuspending {
        startThroughUsbSwitch(
            mayLoseShizuku = mayLoseShizuku,
            start = { attempts.next() },
            awaitShizuku = { isShizukuBack },
            onWaiting = onWaiting,
            didShellDie = { it is ShellDied },
        )
    }

    @Test
    fun `a start that succeeds is not repeated and does not wait`() {
        val attempts = Attempts({ "started" })
        var waits = 0

        val report = startThrough(attempts, onWaiting = { waits++ })

        assertEquals("started", report)
        assertEquals(1, attempts.count)
        assertEquals(0, waits)
    }

    @Test
    fun `a shell process lost to the usb switch is awaited and the start repeated`() {
        val attempts = Attempts({ throw ShellDied() }, { "started" })
        var waits = 0

        val report = startThrough(attempts, onWaiting = { waits++ })

        assertEquals("started", report)
        assertEquals(2, attempts.count)
        assertEquals(1, waits)
    }

    @Test
    fun `a shizuku that does not come back is reported like a failed start`() {
        val attempts = Attempts({ throw ShellDied() })

        val report = JSONObject(startThrough(attempts, isShizukuBack = false))

        assertEquals("ERROR", report.getString("state"))
        assertEquals(SHIZUKU_LOST_TO_USB_SWITCH, report.getString("detail"))
        assertEquals(1, attempts.count)
    }

    @Test
    fun `the second attempt is final`() {
        val attempts = Attempts({ throw ShellDied() }, { throw ShellDied() })

        assertThrows(ShellDied::class.java) { startThrough(attempts) }

        assertEquals(2, attempts.count)
    }

    @Test
    fun `a failure that is not the shell process dying is not repeated`() {
        val attempts = Attempts({ throw IllegalStateException("verifyUpstream") })
        var waits = 0

        val thrown = assertThrows(IllegalStateException::class.java) {
            startThrough(attempts, onWaiting = { waits++ })
        }

        assertEquals("verifyUpstream", thrown.message)
        assertEquals(1, attempts.count)
        assertEquals(0, waits)
    }

    @Test
    fun `a session without usb never waits for shizuku`() {
        val attempts = Attempts({ throw ShellDied() })
        var waits = 0

        assertThrows(ShellDied::class.java) {
            startThrough(attempts, mayLoseShizuku = false, onWaiting = { waits++ })
        }

        assertEquals(1, attempts.count)
        assertEquals(0, waits)
    }

    @Test
    fun `cancelling a start passes straight through`() {
        val attempts = Attempts({ throw CancellationException("stopped") })
        var waits = 0

        assertThrows(CancellationException::class.java) {
            startThrough(attempts, onWaiting = { waits++ })
        }

        assertEquals(1, attempts.count)
        assertEquals(0, waits)
    }

    @Test
    fun `the lost shizuku report says what to do`() {
        val report = JSONObject(startThrough(Attempts({ throw ShellDied() }), isShizukuBack = false))

        assertFalse(report.getString("detail").contains("Exception"))
    }
}
