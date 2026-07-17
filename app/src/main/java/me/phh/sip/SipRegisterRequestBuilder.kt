//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

object SipRegisterRequestBuilder {
    private fun headerValues(headers: SipHeadersMap, name: String): List<String> =
        headers
            .filterKeys { it.equals(name, ignoreCase = true) }
            .values
            .flatten()
            .map { it.toString() }

    private fun sipParam(mechanism: String, name: String): String? =
        mechanism
            .split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.trim()
            ?.trim('"')

    private fun rewriteIpsecEalg(mechanism: String, ealg: String): String =
        mechanism
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("ealg=", ignoreCase = true) }
            .joinToString(";") + ";ealg=$ealg"

    private fun rewriteSecurityVerifyEalg(headers: SipHeadersMap, ealg: String): SipHeadersMap {
        val values = headerValues(headers, "security-verify")
        if (values.isEmpty()) {
            return headers
        }

        val rewritten = values.map { value ->
            value.split(",").joinToString(", ") { mechanism ->
                rewriteIpsecEalg(mechanism, ealg)
            }
        }

        return headers.filterKeys { !it.equals("security-verify", ignoreCase = true) } +
            rewritten.joinToString("\n") { "Security-Verify: $it" }.toSipHeadersMap()
    }

    private fun rewriteSecurityClientEalg(securityClientLine: String, ealg: String): String {
        if (securityClientLine.isBlank()) {
            return securityClientLine
        }

        val prefix = "Security-Client:"
        val payload = securityClientLine
            .substringAfter(prefix, securityClientLine)
            .trim()

        if (payload.isBlank()) {
            return securityClientLine
        }

        val rewritten = payload
            .split(",")
            .joinToString(", ") { mechanism ->
                rewriteIpsecEalg(mechanism, ealg)
            }

        return "$prefix $rewritten"
    }

    private fun selectSecurityClientFromSecurityVerify(
        securityClientLine: String,
        registerHeaders: SipHeadersMap,
    ): String {
        if (securityClientLine.isBlank()) {
            return securityClientLine
        }

        val verifyMechanism = headerValues(registerHeaders, "security-verify")
            .flatMap { it.split(",") }
            .map { it.trim() }
            .firstOrNull { it.startsWith("ipsec-3gpp", ignoreCase = true) }
            ?: return securityClientLine

        val selectedAlg = sipParam(verifyMechanism, "alg") ?: return securityClientLine
        val selectedEalg = sipParam(verifyMechanism, "ealg")

        val prefix = "Security-Client:"
        val payload = securityClientLine
            .substringAfter(prefix, securityClientLine)
            .trim()

        val selectedClient = payload
            .split(",")
            .map { it.trim() }
            .firstOrNull { mechanism ->
                sipParam(mechanism, "alg")?.equals(selectedAlg, ignoreCase = true) == true &&
                    when (selectedEalg) {
                        null -> sipParam(mechanism, "ealg") == null
                        else -> sipParam(mechanism, "ealg")?.equals(selectedEalg, ignoreCase = true) == true
                    }
            }
            ?: return securityClientLine

        return "$prefix $selectedClient"
    }

    private fun stripSecurityVerifyQ(headers: SipHeadersMap): SipHeadersMap = headers

    private fun authorizationLine(akaDigest: String): String =
        if (akaDigest.isBlank()) "" else "Authorization: $akaDigest"

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
        securityClientAlgs: List<String> = listOf("hmac-sha-1-96", "hmac-md5-96"),
        securityClientEalgs: List<String> = listOf("null", "aes-cbc"),
        useSelectedSecurityClient: Boolean = false,
        forceSecurityAgreementNullEalg: Boolean = false,
        stripSecurityVerifyQ: Boolean = false,
        supportGruu: Boolean = true,
        supportSecurityAgreement: Boolean = true,
        registrationExpiresSeconds: Int = 7200,
    ): SipRequest {
        val defaultSecClientLine = if (
            supportSecurityAgreement &&
            (registerCounter == 1 || akaDigest.isNotBlank())
        ) {
            SipSecurityClientHeader.build(
                ipsecSettings = ipsecSettings,
                clientPort = clientPort,
                serverPort = serverPort,
                algs = securityClientAlgs,
                ealgs = securityClientEalgs,
            )
        } else {
            ""
        }
        val effectiveRegisterHeaders = registerHeaders
        val authLine = authorizationLine(akaDigest)
        val baseSecClientLine = securityClientOverride ?: defaultSecClientLine
        val effectiveSecClientLine = baseSecClientLine
        val secClientLine = if (useSelectedSecurityClient) {
            selectSecurityClientFromSecurityVerify(effectiveSecClientLine, effectiveRegisterHeaders)
        } else {
            effectiveSecClientLine
        }
        val supported = if (supportGruu) {
            listOf("path", "gruu", "sec-agree")
        } else {
            listOf("path", "sec-agree")
        }.filterNot { it == "sec-agree" && !supportSecurityAgreement }
            .joinToString(", ")
        val securityAgreementHeaders = if (supportSecurityAgreement) {
            """
                Require: sec-agree
                Proxy-Require: sec-agree
                $secClientLine
            """.trimIndent()
        } else {
            ""
        }
        // P-Access-Network-Info: 3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=216302ee2003a107
        return SipRequest(
            SipMethod.REGISTER,
            "sip:$realm",
            // "sip:lte-lguplus.co.kr",
            effectiveRegisterHeaders +
                """
                Expires: $registrationExpiresSeconds
                Cseq: $registerCounter REGISTER
                Contact: $contact
                Supported: $supported
                Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, MESSAGE, PRACK, OPTIONS
                $authLine
                $securityAgreementHeaders
                """.toSipHeadersMap(),
        ) // route present on all calls except this
    }
}
