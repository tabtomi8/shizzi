package dev.shizzi

import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/*
 * Tethering picks its upstream when its first downstream comes up, and after that only when the
 * default network switches, the current upstream is lost, or the current upstream's
 * LinkProperties change. Preferring test networks sets a flag and nothing more. So a USB
 * downstream that was already serving before the session's test network existed stays on the
 * phone's own connection.
 *
 * Up to Android 15 the USB interface can be untethered and tethered again, which brings the first
 * downstream back up. Android 16 removed that (TetheringManager.tether and untether throw), and the
 * only other way to bounce USB is to switch the USB function, which on many phones restarts adbd
 * and takes Shizuku down with it.
 *
 * What is left is the LinkProperties trigger. Changing the Private DNS setting makes connectivity
 * rewrite the LinkProperties of every network that validates Private DNS, which includes the
 * current upstream, so tethering picks again, and with the preference set it picks the test
 * network. The setting is changed for a moment and then put back.
 */

const val PRIVATE_DNS_MODE = "private_dns_mode"
const val PRIVATE_DNS_SPECIFIER = "private_dns_specifier"
const val PRIVATE_DNS_DEFAULT_MODE = "private_dns_default_mode"

const val PRIVATE_DNS_OFF = "off"
const val PRIVATE_DNS_OPPORTUNISTIC = "opportunistic"
const val PRIVATE_DNS_HOSTNAME = "hostname"

/** The Private DNS settings as `settings get global` reads them; null when unset. */
data class PrivateDnsSetting(val mode: String?, val specifier: String?)

/**
 * The Private DNS setting to hold for a moment so that the upstream's LinkProperties change, or
 * null when there is none. Each choice keeps DNS as private as it was:
 *
 * - Off turns opportunistic: it may use TLS for a moment, never less than before.
 * - Opportunistic turns off: opportunistic already falls back to plain DNS whenever TLS isn't
 *   available, so this is within what it allows.
 * - A provider hostname changes case only. Hostnames are case-insensitive, so it is the same
 *   provider, and DNS stays strict, but the LinkProperties compare the name as written.
 */
fun privateDnsNudgeFor(current: PrivateDnsSetting, defaultMode: String?): PrivateDnsSetting? =
    when (current.mode ?: defaultMode ?: PRIVATE_DNS_OPPORTUNISTIC) {
        PRIVATE_DNS_OFF -> current.copy(mode = PRIVATE_DNS_OPPORTUNISTIC)
        PRIVATE_DNS_HOSTNAME -> current.specifier?.let(::flipCase)?.let { current.copy(specifier = it) }
        else -> current.copy(mode = PRIVATE_DNS_OFF)
    }

private fun flipCase(name: String): String? {
    val upper = name.uppercase(Locale.ROOT)
    if (upper != name) return upper

    return name.lowercase(Locale.ROOT).takeIf { it != name }
}

/** Global settings, as the settings shell command reads and writes them. */
interface GlobalSettings {
    fun get(key: String): String?

    /** Null deletes the key, which is how an unset value is put back. */
    fun put(key: String, value: String?)
}

/**
 * Holds the Private DNS nudge until [awaitMoved] reports the upstream moved or gives up, then
 * puts the setting back as it was. True when the upstream moved.
 */
fun reselectThroughPrivateDns(
    settings: GlobalSettings,
    awaitMoved: () -> Boolean,
    onEvent: (String) -> Unit = {},
    onRestoreFailed: (String) -> Unit = onEvent,
): Boolean {
    val current = PrivateDnsSetting(settings.get(PRIVATE_DNS_MODE), settings.get(PRIVATE_DNS_SPECIFIER))
    val defaultMode = if (current.mode == null) settings.get(PRIVATE_DNS_DEFAULT_MODE) else null

    val nudge = privateDnsNudgeFor(current, defaultMode)
    if (nudge == null) {
        onEvent("private dns gives nothing to change (${describe(current)})")
        return false
    }

    onEvent("changing private dns for a moment (${describe(current)} -> ${describe(nudge)}) so tethering re-picks its upstream")
    try {
        apply(settings, from = current, to = nudge)
        return awaitMoved()
    } finally {
        runCatching { apply(settings, from = nudge, to = current) }
            .onSuccess { onEvent("private dns restored (${describe(current)})") }
            .onFailure { failure ->
                onRestoreFailed(
                    "could not restore private dns to ${describe(current)}: ${failure.message}; " +
                        "set it again under Settings > Network > Private DNS",
                )
            }
    }
}

private fun apply(settings: GlobalSettings, from: PrivateDnsSetting, to: PrivateDnsSetting) {
    if (from.mode != to.mode) settings.put(PRIVATE_DNS_MODE, to.mode)
    if (from.specifier != to.specifier) settings.put(PRIVATE_DNS_SPECIFIER, to.specifier)
}

private fun describe(setting: PrivateDnsSetting): String = when (setting.mode) {
    PRIVATE_DNS_HOSTNAME -> "hostname ${setting.specifier}"
    null -> if (setting.specifier == null) "default" else "default, hostname ${setting.specifier}"
    else -> setting.mode
}

class ShellGlobalSettings(private val deadlineMs: Long = DEFAULT_DEADLINE_MS) : GlobalSettings {

    override fun get(key: String): String? =
        run("get", "global", key).trim().takeUnless { it.isEmpty() || it == "null" }

    override fun put(key: String, value: String?) {
        when (value) {
            null -> run("delete", "global", key)
            else -> run("put", "global", key, value)
        }
    }

    /** Throws unless the command exits cleanly, so a failed read is never taken for an unset key. */
    private fun run(vararg args: String): String {
        val command = listOf("settings") + args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val reader = Executors.newSingleThreadExecutor()

        try {
            val output = reader.submit<String> { process.inputStream.bufferedReader().use { it.readText() } }
            if (!process.waitFor(deadlineMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                error("${command.joinToString(" ")} timed out after ${deadlineMs}ms")
            }

            val text = runCatching { output.get(deadlineMs, TimeUnit.MILLISECONDS) }.getOrDefault("")
            check(process.exitValue() == 0) {
                "${command.joinToString(" ")} exited ${process.exitValue()}: ${text.trim()}"
            }
            return text
        } finally {
            reader.shutdownNow()
        }
    }

    private companion object {
        const val DEFAULT_DEADLINE_MS = 3_000L
    }
}
