//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

object SipRegisterRequestBuilder {
    fun build(
        realm: String,
        registerHeaders: SipHeadersMap,
        registerCounter: Int,
        contact: String,
        akaDigest: String,
        ipsecSettings: SipIpsecSettings,
        clientPort: Int,
        serverPort: Int,
        securityClientOverride: String? = null,
    ): SipRequest {
        val defaultSecClientLine = SipSecurityClientHeader.build(
            ipsecSettings = ipsecSettings,
            clientPort = clientPort,
            serverPort = serverPort,
        )
        val secClientLine = securityClientOverride ?: defaultSecClientLine

        // P-Access-Network-Info: 3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=216302ee2003a107
        // TEMP SingTel REGISTER PANI test.
        // Current clean matrix: original IMPI digest username, challenge realm,
        // Proxy-Require: sec-agree, and Security-Verify q=0.5.
        // This intentionally hardcodes one LTE cell id so we can test whether
        // SingTel protected REGISTER changes from EOF/no-response to a SIP reply.
        val singtelPaniLine =
            if (realm == "ims.mnc001.mcc525.3gppnetwork.org") {
                "P-Access-Network-Info: 3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=5250102C6B611D01"
            } else {
                ""
            }

        // TEMP SingTel REGISTER PVNI test.
        // Keep the current clean matrix and add the common IMS visited-network header.
        val singtelPvniLine =
            if (realm == "ims.mnc001.mcc525.3gppnetwork.org") {
                "P-Visited-Network-ID: ims.mnc001.mcc525.3gppnetwork.org"
            } else {
                ""
            }

        return SipRequest(
            SipMethod.REGISTER,
            "sip:$realm",
            // "sip:lte-lguplus.co.kr",
            registerHeaders +
                """
                Expires: 7200
                Cseq: $registerCounter REGISTER
                Contact: $contact
                $singtelPaniLine
                $singtelPvniLine
                Supported: path, gruu, sec-agree
                Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, MESSAGE, PRACK, OPTIONS
                Authorization: $akaDigest
                Require: sec-agree
                Proxy-Require: sec-agree
                $secClientLine
                """.toSipHeadersMap(),
        ) // route present on all calls except this
    }
}
