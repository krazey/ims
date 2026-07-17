//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.content.Context
import android.telephony.Rlog
import android.telephony.TelephonyManager
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Carrier-specific IMS behavior for the resolved home operator.
 *
 * Code defines safe typed defaults. Operator exceptions are loaded from
 * res/xml/sip_carrier_policies.xml and selected by MCC/MNC and Android carrier
 * ID, following the same defaults-plus-overlays model as AOSP CarrierConfig.
 */

enum class RegistrationForbiddenPcscfPolicy {
    PERMANENT_STOP,
    SAME_PCSCF,
    NEXT_PCSCF;

    companion object {
        fun fromSamsung(raw: String?): RegistrationForbiddenPcscfPolicy? = when (
            raw?.trim()?.lowercase()
        ) {
            "perm_stop" -> PERMANENT_STOP
            "same_pcscf" -> SAME_PCSCF
            "next_pcscf" -> NEXT_PCSCF
            else -> null
        }
    }
}

enum class SipIpVersionPolicy {
    ANY,
    IPV4,
    IPV6,
    IPV4V6;

    fun accepts(address: InetAddress): Boolean = when (this) {
        ANY, IPV4V6 -> address is Inet4Address || address is Inet6Address
        IPV4 -> address is Inet4Address
        IPV6 -> address is Inet6Address
    }

    companion object {
        fun fromSamsung(raw: String): SipIpVersionPolicy = when (raw.lowercase()) {
            "ipv4" -> IPV4
            "ipv6" -> IPV6
            "ipv4v6" -> IPV4V6
            else -> ANY
        }
    }
}

enum class SipTransportPolicy {
    DEFAULT,
    TCP,
    UDP,
    UDP_PREFERRED,
    TLS;

    val requiresUdp: Boolean get() = this == UDP

    companion object {
        fun fromSamsung(raw: String): SipTransportPolicy = when (raw.lowercase()) {
            "tcp" -> TCP
            "udp" -> UDP
            "udp-preferred" -> UDP_PREFERRED
            "tls" -> TLS
            else -> DEFAULT
        }
    }
}

data class SipPreconditionPolicy(
    val cellular: Boolean = true,
    val iwlan: Boolean = false,
) {
    fun enabledFor(registrationTech: Int): Boolean = when (registrationTech) {
        REGISTRATION_TECH_IWLAN -> iwlan
        else -> cellular
    }
}

data class SipRegistrationRecoveryPolicy(
    val blockPcscfOnRegistrationFailure: Boolean = true,
    val pcscfBlockMs: Long = 30_000L,
    val keepFrameworkRegistrationDuringTransientSipReconnect: Boolean = true,
    val retryBaseMs: Long = 30_000L,
    val retryMaxMs: Long = 1_800_000L,
    val forbiddenPcscfPolicy: RegistrationForbiddenPcscfPolicy =
        RegistrationForbiddenPcscfPolicy.NEXT_PCSCF,
)

internal data class SipRegistrationFailureDecision(
    val retry: Boolean,
    val blockCurrentPcscf: Boolean,
    val retryAfterMs: Long? = null,
)

internal object SipRegistrationFailurePolicy {
    fun retryAfterMs(response: SipResponse): Long? = response.headers["retry-after"]
        ?.firstOrNull()
        ?.substringBefore(';')
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it >= 0L }
        ?.let { seconds -> seconds.coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L }

    fun decide(
        response: SipResponse?,
        policy: SipRegistrationRecoveryPolicy,
    ): SipRegistrationFailureDecision {
        val retryAfterMs = response?.let(::retryAfterMs)
        if (response?.statusCode != 403) {
            val retrySamePcscf = response?.statusCode == 503 &&
                retryAfterMs != null && retryAfterMs in 1 until 32_000L
            return SipRegistrationFailureDecision(
                retry = true,
                blockCurrentPcscf = !retrySamePcscf,
                retryAfterMs = retryAfterMs,
            )
        }

        return when (policy.forbiddenPcscfPolicy) {
            RegistrationForbiddenPcscfPolicy.PERMANENT_STOP ->
                SipRegistrationFailureDecision(false, false)
            RegistrationForbiddenPcscfPolicy.SAME_PCSCF ->
                SipRegistrationFailureDecision(true, false, retryAfterMs)
            RegistrationForbiddenPcscfPolicy.NEXT_PCSCF ->
                SipRegistrationFailureDecision(true, true, retryAfterMs)
        }
    }
}

data class SipSmsPolicy(
    val fallbackSipStatusCodes: Set<Int> = setOf(403, 404, 405, 408, 480, 488, 500, 501, 503, 603),
    val fallbackCooldownMs: Long = 30L * 60L * 1000L,
    val rpResultWaitMs: Long = 15_000L,
)

data class SipInviteFailurePolicy(
    val retryAfter422: Boolean = true,
    val retryIllegalSdpStatusCodes: Set<Int> = setOf(400),
    val retryIllegalSdpWarningSubstrings: List<String> = listOf(
        "SDP is illegal",
        "illegal SDP",
    ),
    val reconnectOnAuthFailure: Boolean = true,
    val authFailureStatusCodes: Set<Int> = setOf(500),
    val authFailureMarkerSubstrings: List<String> = listOf(
        "AUTH failure",
        "not authorised",
        "not authorized",
    ),
    val authFailureReconnectDelayMs: Long = 1_000L,
    val reconnectAfterFinalFailureStatusCodes: Set<Int> = emptySet(),
    val reconnectAfterFinalFailureDelayMs: Long = 1_000L,
    val csfbStatusCodes: Set<Int> = emptySet(),
    val csfbStatusRules: Set<String> = emptySet(),
) {
    fun shouldFallbackToCs(statusCode: Int): Boolean =
        statusCode in csfbStatusCodes ||
            csfbStatusRules.any { SipStatusCodeRule.matches(it, statusCode) }
}

internal object SipStatusCodeRule {
    private val statusClass = Regex("^[1-6]xx$", RegexOption.IGNORE_CASE)
    private val excludedStatusClass = Regex(
        "^\\^\\(\\?!.*\\)([1-6])xx$",
        RegexOption.IGNORE_CASE,
    )

    fun matches(rule: String, statusCode: Int): Boolean {
        val normalized = rule.trim()
        normalized.toIntOrNull()?.let { return it == statusCode }
        if (statusClass.matches(normalized)) {
            return statusCode / 100 == normalized.first().digitToInt()
        }

        val statusClassMatch = excludedStatusClass.matchEntire(normalized) ?: return false
        if (statusCode / 100 != statusClassMatch.groupValues[1].toInt()) return false
        val excluded = Regex("\\d{3}").findAll(normalized)
            .map { it.value.toInt() }
            .toSet()
        return statusCode !in excluded
    }
}

data class SipCallSignalingKeepAlivePolicy(
    val outgoingMode: String = "none",
    val incomingMode: String = "none",
    val intervalMs: Long = 8_000L,
    val delayFirstPacket: Boolean = false,
) {
    fun startsForOutgoing(statusCode: Int): Boolean = when (outgoingMode) {
        "outgoing" -> statusCode in 100..199
        "alerting" -> statusCode in 180..199
        else -> false
    }

    val startsForIncoming: Boolean
        get() = incomingMode == "incoming"
}

data class SipCallSetupTimerPolicy(
    val inviteTimeoutMs: Long = 180_000L,
    val ringingTimeoutMs: Long = 30_000L,
    val ringbackTimeoutMs: Long = 32_000L,
)

data class SipPublicNumberNormalizationPolicy(
    val kazakhstanMobileWithoutCountryCode: Boolean = false,
) {
    fun normalizeToE164(stripped: String): String? {
        if (!kazakhstanMobileWithoutCountryCode) {
            return null
        }

        return when {
            // National mobile/public form, seen from IMS as 7000000000.
            stripped.length == 10 && stripped.startsWith("7") -> "+7$stripped"

            // Trunk-prefix form used in Kazakhstan, for example 87000000000.
            stripped.length == 11 && stripped.startsWith("8") &&
                stripped.drop(1).startsWith("7") -> "+7${stripped.drop(1)}"

            // Country-code form without '+', for example 77000000000.
            stripped.length == 11 && stripped.startsWith("7") -> "+$stripped"

            else -> null
        }
    }
}

data class SipCarrierPolicy(
    val mcc: String,
    val mnc: String,
    val isControlSocketUdp: Boolean = false,
    val transportPolicy: SipTransportPolicy = SipTransportPolicy.DEFAULT,
    val ipVersionPolicy: SipIpVersionPolicy = SipIpVersionPolicy.ANY,
    val ipsecSupported: Boolean = true,
    val preconditionPolicy: SipPreconditionPolicy = SipPreconditionPolicy(),
    val roamingSupported: Boolean = true,
    val supportedNetworks: Set<String> = emptySet(),
    val supportedServices: Set<String> = emptySet(),
    val serviceSwitches: Map<String, Boolean> = emptyMap(),
    val requireNonsessAka: Boolean = false,
    val registerExtraHeaders: SipHeadersMap = emptyMap(),
    val subscribeRegEvent: Boolean = true,
    val registerGruuSupported: Boolean = true,
    val forceCsfbDialStrings: Set<String> = emptySet(),
    val plainTelShortCodes: Set<String> = emptySet(),
    val plainTelAllLocalShortCodes: Boolean = false,
    val outgoingPaniPolicy: OutgoingPaniPolicy = OutgoingPaniPolicy.NONE,
    val outgoingInviteShape: OutgoingInviteShape = OutgoingInviteShape.DEFAULT,
    val outgoingTargetUriType: OutgoingTargetUriType = OutgoingTargetUriType.TEL,
    val securityClientAlgs: List<String> = DEFAULT_SECURITY_CLIENT_ALGS,
    val securityClientEalgs: List<String> = DEFAULT_SECURITY_CLIENT_EALGS,
    val minSeSeconds: Int = 90,
    val sessionExpiresSeconds: Int = 1800,
    val registrationExpiresSeconds: Int = 7200,
    val mssSize: Int = 1500,
    val pcscfPreference: Int = 0,
    val sosUrnRequired: Boolean = false,
    val blockDeregistrationOnSrvcc: Boolean = false,
    val lastPaniHeader: String = "",
    val supportedGeolocationPhase: Int = 0,
    val audioCodecs: Set<String> = emptySet(),
    val evsEnabled: Boolean = false,
    val emergencyDomain: String? = null,
    val emergencyCsfbStatusRules: Set<String> = emptySet(),
    val noSimEmergencyDomain: String? = null,
    val supplementaryServiceDomain: String? = null,
    val supplementaryServiceCallForwardUriType: String? = null,
    val srvccVersion: Int? = null,
    val defaultSmsFallbackEnabled: Boolean? = null,
    val iwlanPaniFormat: String? = null,
    val fallbackEmergencyDialStrings: Set<String> = emptySet(),
    val publicNumberNormalizationPolicy: SipPublicNumberNormalizationPolicy =
        SipPublicNumberNormalizationPolicy(),
    val registrationRecoveryPolicy: SipRegistrationRecoveryPolicy = SipRegistrationRecoveryPolicy(),
    val smsPolicy: SipSmsPolicy = SipSmsPolicy(),
    val callSignalingKeepAlivePolicy: SipCallSignalingKeepAlivePolicy =
        SipCallSignalingKeepAlivePolicy(),
    val callSetupTimerPolicy: SipCallSetupTimerPolicy = SipCallSetupTimerPolicy(),
    val inviteFailurePolicy: SipInviteFailurePolicy = SipInviteFailurePolicy(),
) {
    val mccMnc: String = mcc + mnc

    fun isLocalShortCode(normalizedPhoneNumber: String): Boolean =
        normalizedPhoneNumber.length in 3..6 && normalizedPhoneNumber.all { it.isDigit() }

    fun shouldKeepShortServicePlainTel(normalizedPhoneNumber: String): Boolean =
        isLocalShortCode(normalizedPhoneNumber) &&
            (plainTelAllLocalShortCodes || normalizedPhoneNumber in plainTelShortCodes)

    fun shouldForceCsfbForDialString(normalizedPhoneNumber: String): Boolean =
        normalizedPhoneNumber in forceCsfbDialStrings

    fun isFallbackEmergencyDialString(normalizedPhoneNumber: String): Boolean =
        normalizedPhoneNumber in fallbackEmergencyDialStrings

    fun normalizePublicNumberToE164(stripped: String): String? =
        publicNumberNormalizationPolicy.normalizeToE164(stripped)

    fun useUdpControlSocket(): Boolean =
        isControlSocketUdp || transportPolicy.requiresUdp

    fun preconditionEnabled(registrationTech: Int): Boolean =
        preconditionPolicy.enabledFor(registrationTech)

    fun supportsNetwork(registrationTech: Int): Boolean {
        if (supportedNetworks.isEmpty()) return true
        return when (registrationTech) {
            REGISTRATION_TECH_IWLAN -> "wifi" in supportedNetworks
            REGISTRATION_TECH_LTE ->
                "lte" in supportedNetworks || "nr" in supportedNetworks
            else -> true
        }
    }

    fun serviceEnabled(name: String, profileToken: String): Boolean =
        serviceSwitches[name] ?: (supportedServices.isEmpty() || profileToken in supportedServices)

    fun imsEnabled(): Boolean = serviceSwitches["enableIms"] != false

    fun voiceEnabled(registrationTech: Int): Boolean =
        imsEnabled() &&
            supportsNetwork(registrationTech) &&
            when (registrationTech) {
                REGISTRATION_TECH_IWLAN -> serviceEnabled("enableServiceVowifi", "mmtel")
                else -> serviceEnabled("enableServiceVolte", "mmtel")
            }

    fun smsIpEnabled(registrationTech: Int): Boolean =
        imsEnabled() &&
            supportsNetwork(registrationTech) &&
            serviceEnabled("enableServiceSmsip", "smsip")

    fun allowsAudioCodec(vararg names: String): Boolean =
        audioCodecs.isEmpty() || names.any { candidate ->
            audioCodecs.any { it.equals(candidate, ignoreCase = true) }
        }

    fun phoneContextForLocalTelUri(realm: String): String {
        val candidate = realm.trim()
            .removePrefix("sip:")
            .substringBefore(";")
            .substringAfter("@")
            .trim()

        if (candidate.isNotBlank() &&
            candidate.none { it.isWhitespace() || it == '<' || it == '>' || it == '"' } &&
            !candidate.contains(":")) {
            return candidate
        }

        return "ims.mnc${normalizedMncForPhoneContext(mnc)}.mcc${mcc.trim().padStart(3, '0')}.3gppnetwork.org"
    }

    fun outgoingPaniHeaders(registrationTech: Int): SipHeadersMap {
        if (outgoingPaniPolicy != OutgoingPaniPolicy.REGISTRATION_ACCESS_TECH) {
            return emptyMap()
        }

        val paniValue = when (registrationTech) {
            REGISTRATION_TECH_IWLAN -> "IEEE-802.11"
            REGISTRATION_TECH_LTE -> "3GPP-E-UTRAN-FDD"
            else -> null
        }

        return paniValue?.let { mapOf("P-Access-Network-Info" to listOf(it)) } ?: emptyMap()
    }

    fun outgoingTargetUri(telUri: String, realm: String): String =
        when (outgoingTargetUriType) {
            OutgoingTargetUriType.TEL -> telUri
            OutgoingTargetUriType.SIP_USER_PHONE -> {
                val domain = phoneContextForLocalTelUri(realm)
                "sip:${telUri.removePrefix("tel:")}@$domain;user=phone"
            }
        }

    fun useSingTelStockPolicy(realm: String, registerTargetRealm: String = realm): Boolean =
        outgoingInviteShape == OutgoingInviteShape.SINGTEL_COMPACT_STOCK ||
            realm.equals(SINGTEL_HOME_REALM, ignoreCase = true) ||
            realm.equals(SINGTEL_STOCK_REALM, ignoreCase = true) ||
            registerTargetRealm.equals(SINGTEL_STOCK_REALM, ignoreCase = true)

    fun registerSecurityClientAlgs(realm: String, registerTargetRealm: String = realm): List<String> =
        if (useSingTelStockPolicy(realm, registerTargetRealm)) {
            listOf("hmac-sha-1-96")
        } else {
            securityClientAlgs
        }

    fun registerSecurityClientEalgs(realm: String, registerTargetRealm: String = realm): List<String> =
        if (useSingTelStockPolicy(realm, registerTargetRealm)) {
            listOf("null")
        } else {
            securityClientEalgs
        }

    fun singtelPublicSipUri(number: String): String {
        val digits = singtelLocalNumberForPhoneContext(number)
        val e164 = if (digits.startsWith("+") || digits.startsWith("65")) {
            if (digits.startsWith("+")) digits else "+$digits"
        } else {
            "+65$digits"
        }
        return "sip:$e164@$SINGTEL_STOCK_REALM"
    }

    fun singtelSmsc(): String = SINGTEL_STOCK_SMSC

    fun smsRequestUri(realm: String, smsc: String?, smscSipIdentity: String?): String =
        if (useSingTelStockPolicy(realm)) {
            smsc?.let { "sip:${if (it.startsWith("+")) it else "+$it"}@$SINGTEL_STOCK_REALM" }
                ?: "sip:$SINGTEL_STOCK_REALM"
        } else {
            smscSipIdentity ?: "sip:$realm"
        }

    fun smsToUri(realm: String, requestUri: String, smsc: String?, smscSipIdentity: String?): String =
        if (useSingTelStockPolicy(realm)) {
            requestUri
        } else {
            smscSipIdentity ?: smsc?.let { "sip:+$it@$realm" } ?: "sip:$realm"
        }

    private fun singtelLocalNumberForPhoneContext(number: String): String {
        val digits = number.trim().trimStart('+')
        return if (digits.startsWith("65") && digits.length == 10) digits.substring(2) else digits
    }

    enum class OutgoingPaniPolicy {
        NONE,
        REGISTRATION_ACCESS_TECH,
    }

    enum class OutgoingTargetUriType {
        TEL,
        SIP_USER_PHONE,
    }

    enum class OutgoingInviteShape {
        DEFAULT,
        SINGTEL_COMPACT_STOCK,
    }

    companion object {
        val DEFAULT_SECURITY_CLIENT_ALGS = listOf("hmac-sha-1-96", "hmac-md5-96")
        val DEFAULT_SECURITY_CLIENT_EALGS = listOf("null", "aes-cbc")

        private const val SINGTEL_HOME_REALM = "ims.mnc001.mcc525.3gppnetwork.org"
        private const val SINGTEL_STOCK_REALM = "ims.singtel.com"
        private const val SINGTEL_STOCK_SMSC = "+6596197777"

        fun normalizedMncForPhoneContext(mnc: String): String =
            mnc.trim().trimStart('0').ifBlank { "0" }.padStart(3, '0')

        fun defaultFor(mcc: String, mnc: String): SipCarrierPolicy =
            SipCarrierPolicy(mcc = mcc, mnc = mnc)

        fun forHomeOperator(mcc: String, mnc: String): SipCarrierPolicy =
            defaultFor(mcc, mnc)
    }
}

/**
 * Resolved home-operator carrier settings.
 *
 * Keep compatibility forwarding methods here during the policy migration so
 * callers can be moved over incrementally without breaking partial trees.
 */
data class SipCarrierSettings(
    val mcc: String,
    val mnc: String,
    val policy: SipCarrierPolicy,
    val carrierId: Int = TelephonyManager.UNKNOWN_CARRIER_ID,
    val databaseRecord: SipCarrierDatabaseRecord? = null,
) {
    val mccMnc: String get() = policy.mccMnc
    val isControlSocketUdp: Boolean get() = policy.isControlSocketUdp
    val transportPolicy: SipTransportPolicy get() = policy.transportPolicy
    val ipVersionPolicy: SipIpVersionPolicy get() = policy.ipVersionPolicy
    val ipsecSupported: Boolean get() = policy.ipsecSupported
    val registrationExpiresSeconds: Int get() = policy.registrationExpiresSeconds
    val mssSize: Int get() = policy.mssSize
    val requireNonsessAka: Boolean get() = policy.requireNonsessAka
    val registerExtraHeaders: SipHeadersMap get() = policy.registerExtraHeaders
    val subscribeRegEvent: Boolean get() = policy.subscribeRegEvent
    val registerGruuSupported: Boolean get() = policy.registerGruuSupported
    val registrationRecoveryPolicy: SipRegistrationRecoveryPolicy get() = policy.registrationRecoveryPolicy
    val smsPolicy: SipSmsPolicy get() = policy.smsPolicy
    val callSignalingKeepAlivePolicy: SipCallSignalingKeepAlivePolicy
        get() = policy.callSignalingKeepAlivePolicy
    val callSetupTimerPolicy: SipCallSetupTimerPolicy
        get() = policy.callSetupTimerPolicy
    val inviteFailurePolicy: SipInviteFailurePolicy get() = policy.inviteFailurePolicy
    val outgoingTargetUriType: SipCarrierPolicy.OutgoingTargetUriType
        get() = policy.outgoingTargetUriType
    val minSeSeconds: Int get() = policy.minSeSeconds
    val sessionExpiresSeconds: Int get() = policy.sessionExpiresSeconds

    fun useUdpControlSocket(): Boolean = policy.useUdpControlSocket()

    fun imsEnabled(): Boolean = policy.imsEnabled()

    fun preconditionEnabled(registrationTech: Int): Boolean =
        policy.preconditionEnabled(registrationTech)

    fun supportsNetwork(registrationTech: Int): Boolean =
        policy.supportsNetwork(registrationTech)

    fun voiceEnabled(registrationTech: Int): Boolean =
        policy.voiceEnabled(registrationTech)

    fun smsIpEnabled(registrationTech: Int): Boolean =
        policy.smsIpEnabled(registrationTech)

    fun allowsAudioCodec(vararg names: String): Boolean =
        policy.allowsAudioCodec(*names)

    fun effectivePolicyLog(): String {
        val record = databaseRecord
        val profile = record?.voiceProfile
        return "mccmnc=$mccMnc mno=${record?.mapping?.mnoName ?: "none"} " +
            "profile=${profile?.name ?: "default"} source=${record?.source ?: "built-in"} " +
            "transport=${policy.transportPolicy} udp=${useUdpControlSocket()} " +
            "ip=${policy.ipVersionPolicy} ipsec=${policy.ipsecSupported} " +
            "precondition=${policy.preconditionPolicy} roaming=${policy.roamingSupported} " +
            "networks=${policy.supportedNetworks} services=${policy.supportedServices} " +
            "switches=${policy.serviceSwitches} uri=${policy.outgoingTargetUriType} " +
            "subscribe=${policy.subscribeRegEvent} gruu=${policy.registerGruuSupported} " +
            "regExpires=${policy.registrationExpiresSeconds} " +
            "sessionExpires=${policy.sessionExpiresSeconds} minSe=${policy.minSeSeconds} " +
            "mss=${policy.mssSize} codecs=${policy.audioCodecs} evs=${policy.evsEnabled} " +
            "csfb=${policy.inviteFailurePolicy.csfbStatusRules}"
    }

    // Legacy names kept while callers are converted.
    val registerNetworkHeaders: SipHeadersMap get() = policy.registerExtraHeaders
    val skipRegEventSubscribe: Boolean get() = !policy.subscribeRegEvent

    fun isLocalShortCode(normalizedPhoneNumber: String): Boolean =
        policy.isLocalShortCode(normalizedPhoneNumber)

    fun shouldKeepShortServicePlainTel(normalizedPhoneNumber: String): Boolean =
        policy.shouldKeepShortServicePlainTel(normalizedPhoneNumber)

    fun shouldForceCsfbForDialString(normalizedPhoneNumber: String): Boolean =
        policy.shouldForceCsfbForDialString(normalizedPhoneNumber)

    fun shouldForceCsfbForDialCode(normalizedPhoneNumber: String): Boolean =
        shouldForceCsfbForDialString(normalizedPhoneNumber)

    fun isFallbackEmergencyDialString(normalizedPhoneNumber: String): Boolean =
        policy.isFallbackEmergencyDialString(normalizedPhoneNumber)

    fun normalizePublicNumberToE164(stripped: String): String? =
        policy.normalizePublicNumberToE164(stripped)

    fun phoneContextForLocalTelUri(realm: String): String =
        policy.phoneContextForLocalTelUri(realm)

    fun outgoingPaniHeaders(registrationTech: Int): SipHeadersMap =
        policy.outgoingPaniHeaders(registrationTech)

    fun outgoingTargetUri(telUri: String, realm: String): String =
        policy.outgoingTargetUri(telUri, realm)

    fun useSingTelStockPolicy(realm: String, registerTargetRealm: String = realm): Boolean =
        policy.useSingTelStockPolicy(realm, registerTargetRealm)

    fun registerSecurityClientAlgs(realm: String, registerTargetRealm: String = realm): List<String> =
        policy.registerSecurityClientAlgs(realm, registerTargetRealm)

    fun registerSecurityClientEalgs(realm: String, registerTargetRealm: String = realm): List<String> =
        policy.registerSecurityClientEalgs(realm, registerTargetRealm)

    fun singtelPublicSipUri(number: String): String =
        policy.singtelPublicSipUri(number)

    fun singtelSmsc(): String =
        policy.singtelSmsc()

    fun outgoingSmscForImsSms(realm: String, discoveredSmsc: String?): String? =
        if (useSingTelStockPolicy(realm)) singtelSmsc() else discoveredSmsc

    fun smsRequestUri(realm: String, smsc: String?, smscSipIdentity: String?): String =
        policy.smsRequestUri(realm, smsc, smscSipIdentity)

    fun outgoingSmsRequestUri(realm: String, smsc: String?, smscSipIdentity: String?): String =
        smsRequestUri(realm, smsc, smscSipIdentity)

    fun smsToUri(realm: String, requestUri: String, smsc: String?, smscSipIdentity: String?): String =
        policy.smsToUri(realm, requestUri, smsc, smscSipIdentity)

    fun outgoingSmsToUri(realm: String, requestUri: String, smsc: String?, smscSipIdentity: String?): String =
        smsToUri(realm, requestUri, smsc, smscSipIdentity)

    companion object {
        fun isLocalShortCode(normalizedPhoneNumber: String): Boolean =
            normalizedPhoneNumber.length in 3..6 && normalizedPhoneNumber.all { it.isDigit() }

        fun normalizedMncForPhoneContext(mnc: String): String =
            SipCarrierPolicy.normalizedMncForPhoneContext(mnc)

        private fun parseSimOperator(simOperator: String): Pair<String, String> {
            val numeric = simOperator.trim().filter { it.isDigit() }
            if (numeric.length !in 5..6) return "" to ""
            val mcc = numeric.take(3)
            val rawMnc = numeric.drop(3)
            return mcc to rawMnc.padStart(3, '0')
        }

        private fun readTelephonyGroupId(
            telephonyManager: TelephonyManager,
            methodName: String,
        ): String {
            try {
                return TelephonyManager::class.java
                    .getMethod(methodName)
                    .invoke(telephonyManager)
                    ?.toString()
                    .orEmpty()
            } catch (_: ReflectiveOperationException) {
                // GID2 is subscription-scoped on some platform releases.
            } catch (_: SecurityException) {
                return ""
            }

            return try {
                val subscriptionId = TelephonyManager::class.java
                    .getMethod("getSubscriptionId")
                    .invoke(telephonyManager) as Int
                TelephonyManager::class.java
                    .getMethod(methodName, Int::class.javaPrimitiveType!!)
                    .invoke(telephonyManager, subscriptionId)
                    ?.toString()
                    .orEmpty()
            } catch (_: ReflectiveOperationException) {
                ""
            } catch (_: SecurityException) {
                ""
            }
        }

        fun fromSimOperator(simOperator: String): SipCarrierSettings {
            val (mcc, mnc) = parseSimOperator(simOperator)
            val policy = SipCarrierPolicy.defaultFor(mcc, mnc)

            return SipCarrierSettings(
                mcc = mcc,
                mnc = mnc,
                policy = policy,
            )
        }

        fun fromContext(
            context: Context,
            telephonyManager: TelephonyManager,
            simOperator: String,
        ): SipCarrierSettings {
            val (mcc, mnc) = parseSimOperator(simOperator)
            val carrierId = try {
                telephonyManager.simCarrierId
            } catch (_: Throwable) {
                TelephonyManager.UNKNOWN_CARRIER_ID
            }
            val databaseRecord = SipCarrierDatabaseXml.find(
                context = context,
                query = SipCarrierDatabaseQuery(
                    mccMnc = mcc + mnc,
                    imsi = try {
                        telephonyManager.subscriberId.orEmpty()
                    } catch (_: Throwable) {
                        ""
                    },
                    gid1 = readTelephonyGroupId(
                        telephonyManager,
                        "getGroupIdLevel1",
                    ),
                    gid2 = readTelephonyGroupId(
                        telephonyManager,
                        "getGroupIdLevel2",
                    ),
                    spn = try {
                        telephonyManager.simOperatorName.orEmpty()
                    } catch (_: Throwable) {
                        ""
                    },
                ),
            )
            val policy = SipCarrierPolicyXml.apply(
                context = context,
                base = databaseRecord?.applyTo(SipCarrierPolicy.defaultFor(mcc, mnc))
                    ?: SipCarrierPolicy.defaultFor(mcc, mnc),
                carrierId = carrierId,
            )
            return SipCarrierSettings(
                mcc = mcc,
                mnc = mnc,
                policy = policy,
                carrierId = carrierId,
                databaseRecord = databaseRecord,
            ).also { settings ->
                Rlog.i("PHH CarrierPolicy", settings.effectivePolicyLog())
                if (settings.policy.evsEnabled) {
                    Rlog.w(
                        "PHH CarrierPolicy",
                        "Carrier enables EVS but PhhIms has no EVS media codec; " +
                            "falling back to allowed AMR codecs",
                    )
                }
                if (settings.policy.supportedGeolocationPhase > 0) {
                    Rlog.w(
                        "PHH CarrierPolicy",
                        "Carrier requests geolocation phase " +
                            "${settings.policy.supportedGeolocationPhase}; " +
                            "PIDF/geolocation signaling is not implemented",
                    )
                }
                if (settings.transportPolicy == SipTransportPolicy.UDP_PREFERRED) {
                    Rlog.i(
                        "PHH CarrierPolicy",
                        "Samsung udp-preferred remains TCP unless negotiation or a " +
                            "manual overlay explicitly selects UDP",
                    )
                }
            }
        }
    }
}
