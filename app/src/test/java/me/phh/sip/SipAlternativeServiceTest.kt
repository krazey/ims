// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Test

class SipAlternativeServiceTest {
    private fun response(body: String, contact: String? = null) = SipResponse(
        statusCode = 380,
        statusString = "Alternative Service",
        headersParam = contact?.let { mapOf("contact" to listOf(it)) }.orEmpty(),
        body = body.toByteArray(),
    )

    @Test
    fun `parses emergency registration and service URN`() {
        val parsed = SipAlternativeServiceParser.parse(
            response(
                """
                <ims-3gpp><alternative-service>
                  <type>emergency</type>
                  <reason>Emergency &amp; retry</reason>
                  <action>emergency-registration</action>
                </alternative-service></ims-3gpp>
                """.trimIndent(),
                "<urn:service:sos.police>",
            ),
        ) ?: error("Alternative-Service was not parsed")

        require(parsed.action == SipAlternativeServiceAction.EMERGENCY_REGISTRATION)
        require(parsed.requiresImsRetry)
        require(parsed.reason == "Emergency & retry")
        require(parsed.serviceUrn == "urn:service:sos.police")
    }

    @Test
    fun `missing action requests emergency CS fallback`() {
        val parsed = SipAlternativeServiceParser.parse(
            response(
                "<ims-3gpp><alternative-service><type>emergency</type>" +
                    "</alternative-service></ims-3gpp>",
            ),
        ) ?: error("Alternative-Service was not parsed")

        require(parsed.action == SipAlternativeServiceAction.EMERGENCY)
        require(!parsed.requiresImsRetry)
        require(parsed.serviceUrn == "urn:service:sos")
    }

    @Test
    fun `ignores non-emergency and non-380 responses`() {
        require(
            SipAlternativeServiceParser.parse(
                response("<alternative-service><type>normal</type></alternative-service>"),
            ) == null,
        )
        require(
            SipAlternativeServiceParser.parse(
                SipResponse(400, "Bad Request", emptyMap()),
            ) == null,
        )
    }
}
