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
                "register_gruu_supported" to false,
                "require_nonsess_aka" to true,
            ),
            registerHeaders = mapOf("P-Test" to listOf("one")),
        ).applyTo(base)

        require(resolved.isControlSocketUdp)
        require(!resolved.registerGruuSupported)
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
    fun `overlay can replace imported Samsung runtime policy`() {
        val base = SipCarrierPolicy.defaultFor("405", "854").copy(
            transportPolicy = SipTransportPolicy.UDP_PREFERRED,
            ipVersionPolicy = SipIpVersionPolicy.IPV4,
            ipsecSupported = true,
            preconditionPolicy = SipPreconditionPolicy(cellular = false),
            supportedNetworks = setOf("lte"),
            supportedServices = setOf("mmtel"),
            serviceSwitches = mapOf("enableServiceSmsip" to false),
        )
        val resolved = SipCarrierPolicyOverlay(
            booleans = mapOf(
                "control_socket_udp" to false,
                "ipsec_supported" to false,
                "precondition_cellular" to true,
                "enable_service_smsip" to true,
            ),
            strings = mapOf(
                "ip_version_policy" to "ipv6",
            ),
            stringArrays = mapOf(
                "supported_networks" to listOf("wifi"),
                "supported_services" to listOf("mmtel", "smsip"),
                "audio_codecs" to listOf("AMR", "AMR-WB"),
            ),
            longs = mapOf(
                "registration_expires_seconds" to 600L,
                "mss_size" to 1300L,
            ),
        ).applyTo(base)

        require(resolved.transportPolicy == SipTransportPolicy.TCP)
        require(!resolved.useUdpControlSocket())
        require(resolved.ipVersionPolicy == SipIpVersionPolicy.IPV6)
        require(!resolved.ipsecSupported)
        require(resolved.preconditionPolicy.cellular)
        require(resolved.supportedNetworks == setOf("wifi"))
        require(resolved.supportedServices == setOf("mmtel", "smsip"))
        require(resolved.serviceSwitches["enableServiceSmsip"] == true)
        require(resolved.registrationExpiresSeconds == 600)
        require(resolved.mssSize == 1300)
        require(resolved.audioCodecs == setOf("AMR", "AMR-WB"))
    }

    @Test
    fun `Tele2 profile replaces newer firmware call defaults`() {
        val base = SipCarrierPolicy.defaultFor("401", "077").copy(
            registerGruuSupported = true,
            callSetupTimerPolicy = SipCallSetupTimerPolicy(
                ringingTimeoutMs = 90_000L,
                ringbackTimeoutMs = 90_000L,
            ),
            inviteFailurePolicy = SipInviteFailurePolicy(
                csfbStatusCodes = setOf(380, 403, 500, 503, 1117),
            ),
        )
        val resolved = SipCarrierPolicyOverlay(
            booleans = mapOf(
                "register_gruu_supported" to false,
            ),
            longs = mapOf(
                "ringing_timeout_ms" to 120_000L,
                "ringback_timeout_ms" to 120_000L,
            ),
            stringArrays = mapOf(
                "invite_csfb_status_codes" to listOf("380", "403", "1117"),
            ),
        ).applyTo(base)

        require(!resolved.registerGruuSupported)
        require(resolved.callSetupTimerPolicy.ringingTimeoutMs == 120_000L)
        require(resolved.callSetupTimerPolicy.ringbackTimeoutMs == 120_000L)
        require(
            resolved.inviteFailurePolicy.csfbStatusCodes ==
                setOf(380, 403, 1117),
        )
    }

    @Test
    fun `invalid operator does not throw`() {
        val settings = SipCarrierSettings.fromSimOperator("")

        require(settings.mcc.isEmpty())
        require(settings.mnc.isEmpty())
    }

    @Test
    fun `emergency fallback is opt in per carrier`() {
        val base = SipCarrierPolicy.defaultFor("001", "001")
        require(!base.isFallbackEmergencyDialString("911"))

        val resolved = SipCarrierPolicyOverlay(
            stringArrays = mapOf("fallback_emergency_dial_strings" to listOf("911")),
        ).applyTo(base)

        require(resolved.isFallbackEmergencyDialString("911"))
    }
}
