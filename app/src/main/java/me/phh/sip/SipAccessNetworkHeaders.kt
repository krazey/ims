// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.AccessNetworkConstants
import android.telephony.CellIdentityLte
import android.telephony.NetworkRegistrationInfo
import android.telephony.Rlog
import android.telephony.TelephonyManager
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE

internal object SipAccessNetworkHeaders {
    fun forOutgoingInvite(
        logTag: String,
        telephonyManager: TelephonyManager,
        registrationTech: Int,
        policy: SipCarrierPolicy.OutgoingPaniPolicy,
        visitedNetworkPolicy: SipCarrierPolicy.OutgoingVisitedNetworkPolicy,
        visitedNetworkId: String,
    ): SipHeadersMap {
        if (registrationTech != REGISTRATION_TECH_LTE ||
            policy != SipCarrierPolicy.OutgoingPaniPolicy.LTE_CELL_IDENTITY
        ) {
            return emptyMap()
        }

        return try {
            val registration = telephonyManager.serviceState
                ?.networkRegistrationInfoList
                ?.firstOrNull {
                    it.transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN &&
                        (it.domain and NetworkRegistrationInfo.DOMAIN_PS) != 0 &&
                        it.isNetworkRegistered
                }
            val cellIdentity = registration?.cellIdentity as? CellIdentityLte
            if (cellIdentity == null) {
                Rlog.w(logTag, "No registered LTE identity for SIP access headers")
                emptyMap()
            } else {
                lteHeaders(
                    mcc = cellIdentity.mccString,
                    mnc = cellIdentity.mncString,
                    tac = cellIdentity.tac,
                    ci = cellIdentity.ci,
                    visitedNetworkId = visitedNetworkId.takeIf {
                        visitedNetworkPolicy ==
                            SipCarrierPolicy.OutgoingVisitedNetworkPolicy.REGISTRATION_REALM
                    },
                )
            }
        } catch (e: RuntimeException) {
            Rlog.w(logTag, "Could not read LTE identity for SIP access headers", e)
            emptyMap()
        }
    }

    internal fun lteHeaders(
        mcc: String?,
        mnc: String?,
        tac: Int,
        ci: Int,
        visitedNetworkId: String?,
    ): SipHeadersMap {
        val normalizedMcc = mcc
            ?.takeIf { it.length == 3 && it.all(Char::isDigit) }
            ?: return emptyMap()
        val normalizedMnc = mnc
            ?.takeIf { it.length in 2..3 && it.all(Char::isDigit) }
            ?: return emptyMap()
        if (tac !in 0..0xffff || ci !in 0..0x0fffffff) return emptyMap()

        val cellIdentity = normalizedMcc + normalizedMnc +
            tac.toString(16).padStart(4, '0') +
            ci.toString(16).padStart(7, '0')
        val headers = mutableMapOf(
            "P-Access-Network-Info" to listOf(
                "3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=${cellIdentity.uppercase()}",
            ),
        )
        visitedNetworkId?.takeIf(String::isNotBlank)?.let {
            headers["P-Visited-Network-ID"] = listOf("\"$it\"")
        }
        return headers
    }
}
