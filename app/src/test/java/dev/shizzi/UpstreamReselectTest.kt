package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamReselectTest {

    private class FakeSettings(initial: Map<String, String> = emptyMap()) : GlobalSettings {
        val values = initial.toMutableMap()
        val writes = mutableListOf<Pair<String, String?>>()
        var failingPut: String? = null

        override fun get(key: String): String? = values[key]

        override fun put(key: String, value: String?) {
            if (key == failingPut) error("settings put $key failed")
            writes += key to value
            when (value) {
                null -> values.remove(key)
                else -> values[key] = value
            }
        }
    }

    @Test
    fun `off turns to a provider hostname for a moment`() {
        val nudge = privateDnsNudgeFor(PrivateDnsSetting(PRIVATE_DNS_OFF, null), null)

        assertEquals(PrivateDnsSetting(PRIVATE_DNS_HOSTNAME, DEFAULT_NUDGE_HOSTNAME), nudge)
    }

    @Test
    fun `opportunistic turns to a provider hostname for a moment`() {
        val nudge = privateDnsNudgeFor(PrivateDnsSetting(PRIVATE_DNS_OPPORTUNISTIC, "one.one.one.one"), null)

        assertEquals(PrivateDnsSetting(PRIVATE_DNS_HOSTNAME, "one.one.one.one"), nudge)
    }

    @Test
    fun `an unset mode follows the default mode`() {
        assertEquals(
            PrivateDnsSetting(PRIVATE_DNS_HOSTNAME, DEFAULT_NUDGE_HOSTNAME),
            privateDnsNudgeFor(PrivateDnsSetting(null, null), null),
        )
        assertEquals(
            PrivateDnsSetting(PRIVATE_DNS_HOSTNAME, DEFAULT_NUDGE_HOSTNAME),
            privateDnsNudgeFor(PrivateDnsSetting(null, null), PRIVATE_DNS_OFF),
        )
    }

    @Test
    fun `a provider hostname only changes case, so dns stays strict`() {
        val strict = PrivateDnsSetting(PRIVATE_DNS_HOSTNAME, "dns.google")
        assertEquals(PrivateDnsSetting(PRIVATE_DNS_HOSTNAME, "DNS.GOOGLE"), privateDnsNudgeFor(strict, null))

        val upper = PrivateDnsSetting(PRIVATE_DNS_HOSTNAME, "DNS.GOOGLE")
        assertEquals(PrivateDnsSetting(PRIVATE_DNS_HOSTNAME, "dns.google"), privateDnsNudgeFor(upper, null))
    }

    @Test
    fun `a provider without letters turns off for a moment`() {
        assertEquals(
            PrivateDnsSetting(PRIVATE_DNS_OFF, "1.1.1.1"),
            privateDnsNudgeFor(PrivateDnsSetting(PRIVATE_DNS_HOSTNAME, "1.1.1.1"), null),
        )
        assertEquals(
            PrivateDnsSetting(PRIVATE_DNS_OFF, null),
            privateDnsNudgeFor(PrivateDnsSetting(PRIVATE_DNS_HOSTNAME, null), null),
        )
    }

    @Test
    fun `writes the specifier before hostname mode and restores mode before clearing the specifier`() {
        val settings = FakeSettings(mapOf(PRIVATE_DNS_MODE to PRIVATE_DNS_OFF))
        var modeWhileWaiting: String? = null
        var specifierWhileWaiting: String? = null

        val didMove = reselectThroughPrivateDns(settings, awaitMoved = {
            modeWhileWaiting = settings.values[PRIVATE_DNS_MODE]
            specifierWhileWaiting = settings.values[PRIVATE_DNS_SPECIFIER]
            true
        })

        assertTrue(didMove)
        assertEquals(PRIVATE_DNS_HOSTNAME, modeWhileWaiting)
        assertEquals(DEFAULT_NUDGE_HOSTNAME, specifierWhileWaiting)
        assertEquals(mapOf(PRIVATE_DNS_MODE to PRIVATE_DNS_OFF), settings.values)
        assertEquals(
            listOf(
                PRIVATE_DNS_SPECIFIER to DEFAULT_NUDGE_HOSTNAME,
                PRIVATE_DNS_MODE to PRIVATE_DNS_HOSTNAME,
                PRIVATE_DNS_MODE to PRIVATE_DNS_OFF,
                PRIVATE_DNS_SPECIFIER to null,
            ),
            settings.writes,
        )
    }

    @Test
    fun `puts the setting back when the upstream did not move`() {
        val settings = FakeSettings(mapOf(PRIVATE_DNS_MODE to PRIVATE_DNS_OFF))

        assertFalse(reselectThroughPrivateDns(settings, awaitMoved = { false }))
        assertEquals(mapOf(PRIVATE_DNS_MODE to PRIVATE_DNS_OFF), settings.values)
    }

    @Test
    fun `an unset mode is unset again afterwards`() {
        val settings = FakeSettings()

        reselectThroughPrivateDns(settings, awaitMoved = { true })

        assertEquals(emptyMap<String, String>(), settings.values)
        assertEquals(
            listOf(
                PRIVATE_DNS_SPECIFIER to DEFAULT_NUDGE_HOSTNAME,
                PRIVATE_DNS_MODE to PRIVATE_DNS_HOSTNAME,
                PRIVATE_DNS_MODE to null,
                PRIVATE_DNS_SPECIFIER to null,
            ),
            settings.writes,
        )
    }

    @Test
    fun `a strict provider only has its hostname touched`() {
        val settings = FakeSettings(
            mapOf(PRIVATE_DNS_MODE to PRIVATE_DNS_HOSTNAME, PRIVATE_DNS_SPECIFIER to "dns.quad9.net"),
        )

        reselectThroughPrivateDns(settings, awaitMoved = { true })

        assertEquals(
            listOf(PRIVATE_DNS_SPECIFIER to "DNS.QUAD9.NET", PRIVATE_DNS_SPECIFIER to "dns.quad9.net"),
            settings.writes,
        )
    }

    @Test
    fun `puts the setting back when waiting fails`() {
        val settings = FakeSettings(mapOf(PRIVATE_DNS_MODE to PRIVATE_DNS_OPPORTUNISTIC))

        runCatching { reselectThroughPrivateDns(settings, awaitMoved = { error("dumpsys died") }) }

        assertEquals(mapOf(PRIVATE_DNS_MODE to PRIVATE_DNS_OPPORTUNISTIC), settings.values)
    }

    @Test
    fun `reports a setting that could not be put back`() {
        val settings = FakeSettings(mapOf(PRIVATE_DNS_MODE to PRIVATE_DNS_OPPORTUNISTIC))
        val problems = mutableListOf<String>()

        reselectThroughPrivateDns(
            settings,
            awaitMoved = {
                settings.failingPut = PRIVATE_DNS_MODE
                true
            },
            onRestoreFailed = { problems += it },
        )

        assertEquals(1, problems.size)
        assertTrue(problems.single(), problems.single().contains("could not restore private dns"))
    }
}
