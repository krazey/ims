// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Test

class SipDispatcherTest {
    private fun response(callId: String, cseq: String): SipResponse = SipResponse(
        statusCode = 200,
        statusString = "OK",
        headersParam = mapOf(
            "call-id" to listOf(callId),
            "cseq" to listOf(cseq),
        ),
        autofill = false,
    )

    @Test
    fun `completed callback does not remove replacement`() {
        val dispatcher = SipDispatcher("test")
        var replacementCalled = false
        dispatcher.setResponseCallback("dialog") {
            dispatcher.setResponseCallback("dialog") {
                replacementCalled = true
                true
            }
            true
        }

        dispatcher.handleResponse(response("dialog", "1 OPTIONS"))
        dispatcher.handleResponse(response("dialog", "2 OPTIONS"))

        require(replacementCalled)
    }

    @Test
    fun `transaction callback requires exact cseq and method`() {
        val dispatcher = SipDispatcher("test")
        var fallbackCalls = 0
        var transactionCalls = 0
        dispatcher.setResponseCallback("dialog") {
            fallbackCalls++
            false
        }
        dispatcher.setResponseCallback("dialog", 7, SipMethod.INVITE) {
            transactionCalls++
            true
        }

        dispatcher.handleResponse(response("dialog", "6 INVITE"))
        dispatcher.handleResponse(response("dialog", "7 UPDATE"))
        dispatcher.handleResponse(response("dialog", "7 INVITE"))

        require(fallbackCalls == 2)
        require(transactionCalls == 1)
    }
}
