//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

object SipDialogHeaderBuilder {
    fun responseHeadersFromRequest(
        request: SipRequest,
        toOverride: List<String>? = null,
        extra: SipHeadersMap = emptyMap(),
    ): SipHeadersMap {
        val base = request.headers.filter { (k, _) ->
            k in listOf("via", "from", "to", "call-id", "cseq", "record-route")
        }
        val tagged = if (toOverride != null) base + ("to" to toOverride) else base
        return tagged + extra
    }

    fun localDialogHeadersForRequest(
        call: SipHandler.Call,
        method: SipMethod,
        commonHeaders: SipHeadersMap,
        contact: String,
    ): SipHeadersMap {
        val cseq = call.localCseq.getAndIncrement()
        val base = commonHeaders - "route" - "security-verify" - "require" -
            "proxy-require" - "content-type" - "content-length" - "record-route" -
            "rseq" - "p-access-network-info"
        val directionHeaders = if (call.outgoing) {
            mapOf(
                "from" to call.callHeaders["from"]!!,
                "to" to call.callHeaders["to"]!!,
            )
        } else {
            mapOf(
                // For an incoming dialog, local side is the original To, remote side is the original From.
                "from" to call.callHeaders["to"]!!,
                "to" to call.callHeaders["from"]!!,
            )
        }
        val routeSet = call.callHeaders["route"]?.let { route ->
            // Confirmed outgoing dialogs store their route set as Route after the final 200 OK.
            mapOf("route" to route)
        } ?: call.callHeaders["record-route"]?.let { rr ->
            // A UAS uses the incoming INVITE Record-Route values in request order.
            mapOf("route" to rr)
        } ?: emptyMap()
        val securityHeaders = commonHeaders["security-verify"]?.let { securityVerify ->
            // This stack registers with sec-agree/IPsec. Some P-CSCFs also require the
            // negotiated Security-Verify header on later in-dialog requests such as UPDATE.
            mapOf(
                "security-verify" to securityVerify,
                "require" to listOf("sec-agree"),
            )
        } ?: emptyMap()
        return base + directionHeaders + routeSet + securityHeaders + mapOf("call-id" to call.callHeaders["call-id"]!!) +
            """
            Contact: $contact
            CSeq: $cseq $method
            Content-Length: 0
            """.toSipHeadersMap()
    }
}
