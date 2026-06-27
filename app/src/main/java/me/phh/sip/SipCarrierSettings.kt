//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE

/**
 * Carrier-specific IMS behavior for the resolved home operator.
 *
 * This is intentionally an in-code policy table for now. It gives PhhIms the
 * same shape as AOSP ImsStack carrier_config defaults/overrides without adding
 * an XML/resource loader before the policy surface has stabilized.
 */

data class SipRegistrationRecoveryPolicy(
    val blockPcscfOnRegistrationFailure: Boolean = true,
    val pcscfBlockMs: Long = 30_000L,
    val keepFrameworkRegistrationDuringTransientSipReconnect: Boolean = true,
)

data class SipCarrierPolicy(
    val mcc: String,
    val mnc: String,
    val isControlSocketUdp: Boolean = false,
    val requireNonsessAka: Boolean = false,
    val registerExtraHeaders: SipHeadersMap = emptyMap(),
    val subscribeRegEvent: Boolean = true,
    val forceCsfbDialStrings: Set<String> = emptySet(),
    val plainTelShortCodes: Set<String> = emptySet(),
    val plainTelAllLocalShortCodes: Boolean = false,
    val outgoingPaniPolicy: OutgoingPaniPolicy = OutgoingPaniPolicy.NONE,
    val outgoingInviteShape: OutgoingInviteShape = OutgoingInviteShape.DEFAULT,
    val securityClientAlgs: List<String> = DEFAULT_SECURITY_CLIENT_ALGS,
    val securityClientEalgs: List<String> = DEFAULT_SECURITY_CLIENT_EALGS,
    val fallbackEmergencyDialStrings: Set<String> = DEFAULT_FALLBACK_EMERGENCY_DIAL_STRINGS,
    val registrationRecoveryPolicy: SipRegistrationRecoveryPolicy = SipRegistrationRecoveryPolicy(),
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

        val DEFAULT_FALLBACK_EMERGENCY_DIAL_STRINGS = setOf(
            "000", // AU and others
            "110", // DE police and others
            "112", // EU/common emergency
            "118",
            "119",
            "911", // NANP/common emergency
            "999", // UK/common emergency
        )

        fun normalizedMncForPhoneContext(mnc: String): String =
            mnc.trim().trimStart('0').ifBlank { "0" }.padStart(3, '0')

        fun defaultFor(mcc: String, mnc: String): SipCarrierPolicy =
            SipCarrierPolicy(mcc = mcc, mnc = mnc)

        fun forHomeOperator(mcc: String, mnc: String): SipCarrierPolicy {
            val mccMnc = mcc + mnc
            return when (mccMnc) {
                "219010" -> defaultFor(mcc, mnc).copy(
                    // A1 HR needs stock access-network headers on REGISTER and
                    // does not require the optional reg-event SUBSCRIBE to mark
                    // IMS voice ready.
                    registerExtraHeaders = mapOf(
                        "P-Access-Network-Info" to listOf("3GPP-E-UTRAN-FDD"),
                        "P-Visited-Network-ID" to listOf("\"ims.mnc010.mcc219.3gppnetwork.org\""),
                    ),
                    subscribeRegEvent = false,
                )

                "232005" -> defaultFor(mcc, mnc).copy(
                    // 3 AT service code 333 is not a normal IMS MMTel target.
                    forceCsfbDialStrings = setOf("333"),
                )

                "286002" -> defaultFor(mcc, mnc).copy(
                    // Vodafone TR confirmed short service codes as plain TEL
                    // and needs access-tech PANI on outgoing INVITE.
                    plainTelShortCodes = setOf("542"),
                    outgoingPaniPolicy = OutgoingPaniPolicy.REGISTRATION_ACCESS_TECH,
                )

                "450006" -> defaultFor(mcc, mnc).copy(
                    // LG U+ can only do UDP and requires non-session AKA.
                    isControlSocketUdp = true,
                    requireNonsessAka = true,
                )

                "525001" -> defaultFor(mcc, mnc).copy(
                    // SingTel needs a stock-like compact outgoing INVITE/SMS
                    // target and restricted security offer.
                    outgoingInviteShape = OutgoingInviteShape.SINGTEL_COMPACT_STOCK,
                    securityClientAlgs = listOf("hmac-sha-1-96"),
                    securityClientEalgs = listOf("null"),
                )

                "208010" -> defaultFor(mcc, mnc).copy(
                    // 20810 can do TCP and UDP; force UDP for testing.
                    isControlSocketUdp = true,
                )

                else -> defaultFor(mcc, mnc)
            }
        }
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
) {
    val mccMnc: String get() = policy.mccMnc
    val isControlSocketUdp: Boolean get() = policy.isControlSocketUdp
    val requireNonsessAka: Boolean get() = policy.requireNonsessAka
    val registerExtraHeaders: SipHeadersMap get() = policy.registerExtraHeaders
    val subscribeRegEvent: Boolean get() = policy.subscribeRegEvent
    val registrationRecoveryPolicy: SipRegistrationRecoveryPolicy get() = policy.registrationRecoveryPolicy

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

    fun phoneContextForLocalTelUri(realm: String): String =
        policy.phoneContextForLocalTelUri(realm)

    fun outgoingPaniHeaders(registrationTech: Int): SipHeadersMap =
        policy.outgoingPaniHeaders(registrationTech)

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

        fun fromSimOperator(simOperator: String): SipCarrierSettings {
            val mcc = simOperator.substring(0 until 3)
            val mnc = simOperator.substring(3).let { if (it.length == 2) "0$it" else it }
            val policy = SipCarrierPolicy.forHomeOperator(mcc, mnc)

            return SipCarrierSettings(
                mcc = mcc,
                mnc = mnc,
                policy = policy,
            )
        }
    }
}
