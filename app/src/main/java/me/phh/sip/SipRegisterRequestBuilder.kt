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
    ): SipRequest {
        val secClientLine = SipSecurityClientHeader.build(
            ipsecSettings = ipsecSettings,
            clientPort = clientPort,
            serverPort = serverPort,
        )

        // P-Access-Network-Info: 3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=216302ee2003a107
        return SipRequest(
            SipMethod.REGISTER,
            "sip:$realm",
            // "sip:lte-lguplus.co.kr",
            registerHeaders +
                """
                Expires: 7200
                Cseq: $registerCounter REGISTER
                Contact: $contact
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
