// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Test

class SipRegisteredIdentityTest {
    @Test
    fun `parses SIP and TEL associated identities`() {
        val response = SipResponse(
            statusCode = 200,
            statusString = "OK",
            headersParam = mapOf(
                "service-route" to listOf("<sip:pcscf.example;lr;transport=udp>"),
                "p-associated-uri" to listOf(
                    "<sip:+1234@ims.example;user=phone>, <tel:+1234>",
                ),
            ),
        )

        val identity = requireNotNull(SipRegisterSuccessParser.parse(response))
        require(identity.mySip == "sip:+1234@ims.example;user=phone")
        require(identity.myTel == "+1234")
        require(identity.route == listOf("<sip:pcscf.example;lr>"))
    }

    @Test
    fun `falls back to SIP user when TEL identity is absent`() {
        val response = SipResponse(
            statusCode = 200,
            statusString = "OK",
            headersParam = mapOf(
                "p-associated-uri" to listOf("sip:+5678@ims.example;user=phone"),
            ),
        )

        val identity = requireNotNull(SipRegisterSuccessParser.parse(response))
        require(identity.mySip == "sip:+5678@ims.example")
        require(identity.myTel == "+5678")
    }

    @Test
    fun `rejects missing or TEL-only associated identity`() {
        val missing = SipResponse(200, "OK", emptyMap())
        val telOnly = SipResponse(
            200,
            "OK",
            mapOf("p-associated-uri" to listOf("<tel:+1234>")),
        )

        require(SipRegisterSuccessParser.parse(missing) == null)
        require(SipRegisterSuccessParser.parse(telOnly) == null)
    }
}
