// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

    @Test
    fun `closed transport drops only its request writers`() {
        val dispatcher = SipDispatcher("test")
        val firstWriter = ByteArrayOutputStream()
        val secondWriter = ByteArrayOutputStream()
        val firstRequest = "OPTIONS sip:test@example.test SIP/2.0\r\n" +
            "Call-ID: first\r\nCSeq: 1 OPTIONS\r\nContent-Length: 0\r\n\r\n"
        val secondRequest = "OPTIONS sip:test@example.test SIP/2.0\r\n" +
            "Call-ID: second\r\nCSeq: 2 OPTIONS\r\nContent-Length: 0\r\n\r\n"

        dispatcher.parseMessage(
            ByteArrayInputStream(firstRequest.toByteArray()).sipReader(),
            firstWriter,
        )
        dispatcher.parseMessage(
            ByteArrayInputStream(secondRequest.toByteArray()).sipReader(),
            secondWriter,
        )
        dispatcher.removeWritersFor(firstWriter)

        require(!dispatcher.hasWriterForCallId("first"))
        require(dispatcher.hasWriterForCallId("second"))
    }
}
