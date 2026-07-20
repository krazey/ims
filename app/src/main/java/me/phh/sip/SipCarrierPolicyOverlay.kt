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
        val controlSocketUdp = booleans["control_socket_udp"]
        val transportPolicy = strings["transport_policy"]
            ?.let { enumValueOrNull<SipTransportPolicy>(it) }
            ?: controlSocketUdp?.let {
                if (it) SipTransportPolicy.UDP else SipTransportPolicy.TCP
            }
            ?: base.transportPolicy
        val preconditionPolicy = base.preconditionPolicy.copy(
            cellular = booleans["precondition_cellular"]
                ?: base.preconditionPolicy.cellular,
            iwlan = booleans["precondition_iwlan"]
                ?: base.preconditionPolicy.iwlan,
        )
        val serviceSwitches = base.serviceSwitches.toMutableMap().apply {
            listOf(
                "enable_ims" to "enableIms",
                "enable_service_volte" to "enableServiceVolte",
                "enable_service_vowifi" to "enableServiceVowifi",
                "enable_service_smsip" to "enableServiceSmsip",
            ).forEach { (overlayName, policyName) ->
                booleans[overlayName]?.let { put(policyName, it) }
            }
        }
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
            retryBaseMs = longs["registration_retry_base_ms"]
                ?: base.registrationRecoveryPolicy.retryBaseMs,
            retryMaxMs = longs["registration_retry_max_ms"]
                ?: base.registrationRecoveryPolicy.retryMaxMs,
            forbiddenPcscfPolicy = strings["registration_403_pcscf_policy"]
                ?.let { enumValueOrNull<RegistrationForbiddenPcscfPolicy>(it) }
                ?: base.registrationRecoveryPolicy.forbiddenPcscfPolicy,
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
            csfbStatusCodes = intSet(
                "invite_csfb_status_codes",
                base.inviteFailurePolicy.csfbStatusCodes,
            ),
            csfbStatusRules = stringArrays["invite_csfb_status_codes"]
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.toSet()
                ?: base.inviteFailurePolicy.csfbStatusRules,
        )
        val callSetupTimerPolicy = base.callSetupTimerPolicy.copy(
            inviteTimeoutMs = longs["invite_timeout_ms"]
                ?: base.callSetupTimerPolicy.inviteTimeoutMs,
            ringingTimeoutMs = longs["ringing_timeout_ms"]
                ?: base.callSetupTimerPolicy.ringingTimeoutMs,
            ringbackTimeoutMs = longs["ringback_timeout_ms"]
                ?: base.callSetupTimerPolicy.ringbackTimeoutMs,
        )

        return base.copy(
            isControlSocketUdp = controlSocketUdp ?: base.isControlSocketUdp,
            transportPolicy = transportPolicy,
            ipVersionPolicy = strings["ip_version_policy"]
                ?.let { enumValueOrNull<SipIpVersionPolicy>(it) }
                ?: base.ipVersionPolicy,
            ipsecSupported = booleans["ipsec_supported"]
                ?: base.ipsecSupported,
            udpResponseFlowPolicy = strings["udp_response_flow_policy"]
                ?.let { enumValueOrNull<SipUdpResponseFlowPolicy>(it) }
                ?: base.udpResponseFlowPolicy,
            preconditionPolicy = preconditionPolicy,
            roamingSupported = booleans["roaming_supported"]
                ?: base.roamingSupported,
            supportedNetworks = stringArrays["supported_networks"]
                ?.map { it.trim().lowercase() }
                ?.filter(String::isNotEmpty)
                ?.toSet()
                ?: base.supportedNetworks,
            supportedServices = stringArrays["supported_services"]
                ?.map { it.trim().lowercase() }
                ?.filter(String::isNotEmpty)
                ?.toSet()
                ?: base.supportedServices,
            serviceSwitches = serviceSwitches,
            requireNonsessAka = booleans["require_nonsess_aka"]
                ?: base.requireNonsessAka,
            registerExtraHeaders = base.registerExtraHeaders + registerHeaders,
            subscribeRegEvent = booleans["subscribe_reg_event"]
                ?: base.subscribeRegEvent,
            registerGruuSupported = booleans["register_gruu_supported"]
                ?: base.registerGruuSupported,
            forceCsfbDialStrings = stringArrays["force_csfb_dial_strings"]
                ?.toSet() ?: base.forceCsfbDialStrings,
            plainTelShortCodes = stringArrays["plain_tel_short_codes"]
                ?.toSet() ?: base.plainTelShortCodes,
            plainTelAllLocalShortCodes = booleans["plain_tel_all_local_short_codes"]
                ?: base.plainTelAllLocalShortCodes,
            outgoingPaniPolicy = strings["outgoing_pani_policy"]
                ?.let { enumValueOrNull<SipCarrierPolicy.OutgoingPaniPolicy>(it) }
                ?: base.outgoingPaniPolicy,
            outgoingVisitedNetworkPolicy = strings[
                "outgoing_visited_network_policy"
            ]?.let {
                enumValueOrNull<SipCarrierPolicy.OutgoingVisitedNetworkPolicy>(it)
            } ?: base.outgoingVisitedNetworkPolicy,
            outgoingInviteShape = strings["outgoing_invite_shape"]
                ?.let { enumValueOrNull<SipCarrierPolicy.OutgoingInviteShape>(it) }
                ?: base.outgoingInviteShape,
            outgoingTargetUriType = strings["outgoing_target_uri_type"]
                ?.let { enumValueOrNull<SipCarrierPolicy.OutgoingTargetUriType>(it) }
                ?: base.outgoingTargetUriType,
            outgoingTargetDomainPolicy = strings["outgoing_target_domain_policy"]
                ?.let {
                    enumValueOrNull<SipCarrierPolicy.OutgoingTargetDomainPolicy>(it)
                }
                ?: base.outgoingTargetDomainPolicy,
            securityClientAlgs = stringArrays["security_client_algs"]
                ?: base.securityClientAlgs,
            securityClientEalgs = stringArrays["security_client_ealgs"]
                ?: base.securityClientEalgs,
            minSeSeconds = longs["min_se_seconds"]?.toInt() ?: base.minSeSeconds,
            sessionExpiresSeconds = longs["session_expires_seconds"]?.toInt()
                ?: base.sessionExpiresSeconds,
            registrationExpiresSeconds = longs["registration_expires_seconds"]
                ?.toInt() ?: base.registrationExpiresSeconds,
            mssSize = longs["mss_size"]?.toInt() ?: base.mssSize,
            pcscfPreference = longs["pcscf_preference"]?.toInt()
                ?: base.pcscfPreference,
            sosUrnRequired = booleans["sos_urn_required"]
                ?: base.sosUrnRequired,
            blockDeregistrationOnSrvcc = booleans["block_deregistration_on_srvcc"]
                ?: base.blockDeregistrationOnSrvcc,
            lastPaniHeader = strings["last_pani_header"]
                ?: base.lastPaniHeader,
            supportedGeolocationPhase = longs["supported_geolocation_phase"]
                ?.toInt() ?: base.supportedGeolocationPhase,
            audioCodecs = stringArrays["audio_codecs"]
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.toSet()
                ?: base.audioCodecs,
            evsEnabled = booleans["evs_enabled"] ?: base.evsEnabled,
            fallbackEmergencyDialStrings = stringArrays["fallback_emergency_dial_strings"]
                ?.toSet() ?: base.fallbackEmergencyDialStrings,
            publicNumberNormalizationPolicy = publicNumberPolicy,
            registrationRecoveryPolicy = recoveryPolicy,
            smsPolicy = smsPolicy,
            callSetupTimerPolicy = callSetupTimerPolicy,
            inviteFailurePolicy = inviteFailurePolicy,
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(raw: String): T? =
        enumValues<T>().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
}
