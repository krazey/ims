// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Test

class SipCarrierPolicyOverlayTest {
    @Test
    fun `overlay changes named values and preserves defaults`() {
        val base = SipCarrierPolicy.defaultFor("450", "006")
        val resolved = SipCarrierPolicyOverlay(
            booleans = mapOf(
                "control_socket_udp" to true,
                "require_nonsess_aka" to true,
            ),
            registerHeaders = mapOf("P-Test" to listOf("one")),
        ).applyTo(base)

        require(resolved.isControlSocketUdp)
        require(resolved.requireNonsessAka)
        require(resolved.registerExtraHeaders["P-Test"] == listOf("one"))
        require(resolved.subscribeRegEvent == base.subscribeRegEvent)
        require(resolved.smsPolicy == base.smsPolicy)
    }

    @Test
    fun `overlay converts status arrays and policy enums`() {
        val resolved = SipCarrierPolicyOverlay(
            strings = mapOf(
                "outgoing_pani_policy" to "registration_access_tech",
                "outgoing_invite_shape" to "singtel_compact_stock",
            ),
            stringArrays = mapOf(
                "sms_fallback_sip_status_codes" to listOf("403", "503", "bad"),
                "plain_tel_short_codes" to listOf("542"),
            ),
            longs = mapOf("sms_rp_result_wait_ms" to 20_000L),
        ).applyTo(SipCarrierPolicy.defaultFor("286", "002"))

        require(
            resolved.outgoingPaniPolicy ==
                SipCarrierPolicy.OutgoingPaniPolicy.REGISTRATION_ACCESS_TECH,
        )
        require(
            resolved.outgoingInviteShape ==
                SipCarrierPolicy.OutgoingInviteShape.SINGTEL_COMPACT_STOCK,
        )
        require(resolved.smsPolicy.fallbackSipStatusCodes == setOf(403, 503))
        require(resolved.smsPolicy.rpResultWaitMs == 20_000L)
        require(resolved.plainTelShortCodes == setOf("542"))
    }

    @Test
    fun `invalid operator does not throw`() {
        val settings = SipCarrierSettings.fromSimOperator("")

        require(settings.mcc.isEmpty())
        require(settings.mnc.isEmpty())
    }
}
