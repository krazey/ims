// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

/**
 * Data-only carrier policy overlay.
 *
 * The string keys intentionally mirror the XML names so new policy files do
 * not require a second carrier-specific code path. Unknown keys are ignored by
 * [applyTo], allowing newer policy files to remain readable by older builds.
 */
internal data class SipCarrierPolicyOverlay(
    val booleans: Map<String, Boolean> = emptyMap(),
    val longs: Map<String, Long> = emptyMap(),
    val strings: Map<String, String> = emptyMap(),
    val stringArrays: Map<String, List<String>> = emptyMap(),
    val registerHeaders: SipHeadersMap = emptyMap(),
) {
    private fun intSet(name: String, fallback: Set<Int>): Set<Int> =
        stringArrays[name]
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            ?: fallback

    fun applyTo(base: SipCarrierPolicy): SipCarrierPolicy {
        val publicNumberPolicy = base.publicNumberNormalizationPolicy.copy(
            kazakhstanMobileWithoutCountryCode = booleans[
                "kazakhstan_mobile_without_country_code"
            ] ?: base.publicNumberNormalizationPolicy.kazakhstanMobileWithoutCountryCode,
        )
        val recoveryPolicy = base.registrationRecoveryPolicy.copy(
            blockPcscfOnRegistrationFailure = booleans[
                "block_pcscf_on_registration_failure"
            ] ?: base.registrationRecoveryPolicy.blockPcscfOnRegistrationFailure,
            pcscfBlockMs = longs["pcscf_block_ms"]
                ?: base.registrationRecoveryPolicy.pcscfBlockMs,
            keepFrameworkRegistrationDuringTransientSipReconnect = booleans[
                "keep_framework_registration_during_transient_sip_reconnect"
            ] ?: base.registrationRecoveryPolicy.keepFrameworkRegistrationDuringTransientSipReconnect,
        )
        val smsPolicy = base.smsPolicy.copy(
            fallbackSipStatusCodes = intSet(
                "sms_fallback_sip_status_codes",
                base.smsPolicy.fallbackSipStatusCodes,
            ),
            fallbackCooldownMs = longs["sms_fallback_cooldown_ms"]
                ?: base.smsPolicy.fallbackCooldownMs,
            rpResultWaitMs = longs["sms_rp_result_wait_ms"]
                ?: base.smsPolicy.rpResultWaitMs,
        )
        val inviteFailurePolicy = base.inviteFailurePolicy.copy(
            retryAfter422 = booleans["invite_retry_after_422"]
                ?: base.inviteFailurePolicy.retryAfter422,
            retryIllegalSdpStatusCodes = intSet(
                "invite_retry_illegal_sdp_status_codes",
                base.inviteFailurePolicy.retryIllegalSdpStatusCodes,
            ),
            retryIllegalSdpWarningSubstrings = stringArrays[
                "invite_retry_illegal_sdp_warning_substrings"
            ] ?: base.inviteFailurePolicy.retryIllegalSdpWarningSubstrings,
            reconnectOnAuthFailure = booleans["invite_reconnect_on_auth_failure"]
                ?: base.inviteFailurePolicy.reconnectOnAuthFailure,
            authFailureStatusCodes = intSet(
                "invite_auth_failure_status_codes",
                base.inviteFailurePolicy.authFailureStatusCodes,
            ),
            authFailureMarkerSubstrings = stringArrays[
                "invite_auth_failure_marker_substrings"
            ] ?: base.inviteFailurePolicy.authFailureMarkerSubstrings,
            authFailureReconnectDelayMs = longs["invite_auth_failure_reconnect_delay_ms"]
                ?: base.inviteFailurePolicy.authFailureReconnectDelayMs,
            reconnectAfterFinalFailureStatusCodes = intSet(
                "invite_reconnect_after_final_failure_status_codes",
                base.inviteFailurePolicy.reconnectAfterFinalFailureStatusCodes,
            ),
            reconnectAfterFinalFailureDelayMs = longs[
                "invite_reconnect_after_final_failure_delay_ms"
            ] ?: base.inviteFailurePolicy.reconnectAfterFinalFailureDelayMs,
        )

        return base.copy(
            isControlSocketUdp = booleans["control_socket_udp"]
                ?: base.isControlSocketUdp,
            requireNonsessAka = booleans["require_nonsess_aka"]
                ?: base.requireNonsessAka,
            registerExtraHeaders = base.registerExtraHeaders + registerHeaders,
            subscribeRegEvent = booleans["subscribe_reg_event"]
                ?: base.subscribeRegEvent,
            forceCsfbDialStrings = stringArrays["force_csfb_dial_strings"]
                ?.toSet() ?: base.forceCsfbDialStrings,
            plainTelShortCodes = stringArrays["plain_tel_short_codes"]
                ?.toSet() ?: base.plainTelShortCodes,
            plainTelAllLocalShortCodes = booleans["plain_tel_all_local_short_codes"]
                ?: base.plainTelAllLocalShortCodes,
            outgoingPaniPolicy = strings["outgoing_pani_policy"]
                ?.let { enumValueOrNull<SipCarrierPolicy.OutgoingPaniPolicy>(it) }
                ?: base.outgoingPaniPolicy,
            outgoingInviteShape = strings["outgoing_invite_shape"]
                ?.let { enumValueOrNull<SipCarrierPolicy.OutgoingInviteShape>(it) }
                ?: base.outgoingInviteShape,
            securityClientAlgs = stringArrays["security_client_algs"]
                ?: base.securityClientAlgs,
            securityClientEalgs = stringArrays["security_client_ealgs"]
                ?: base.securityClientEalgs,
            fallbackEmergencyDialStrings = stringArrays["fallback_emergency_dial_strings"]
                ?.toSet() ?: base.fallbackEmergencyDialStrings,
            publicNumberNormalizationPolicy = publicNumberPolicy,
            registrationRecoveryPolicy = recoveryPolicy,
            smsPolicy = smsPolicy,
            inviteFailurePolicy = inviteFailurePolicy,
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(raw: String): T? =
        enumValues<T>().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
}
