// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

internal object SipCarrierDiagnostics {
    private val responseDiagnosticHeaders = listOf(
        "reason",
        "warning",
        "retry-after",
        "server",
        "allow",
        "supported",
        "require",
    )

    fun responseHeaders(response: SipResponse): String = responseDiagnosticHeaders
        .mapNotNull { name ->
            response.headers[name]
                ?.joinToString(" | ")
                ?.replace('\r', ' ')
                ?.replace('\n', ' ')
                ?.take(512)
                ?.let { "$name=$it" }
        }
        .joinToString(" ")
        .ifBlank { "none" }

    fun requestShape(request: SipRequest): String {
        val headerNames = request.headers.keys
            .map { it.lowercase() }
            .distinct()
            .sorted()
        val sdpShape = request.body
            .toString(Charsets.US_ASCII)
            .lineSequence()
            .filter { line ->
                line.startsWith("m=", ignoreCase = true) ||
                    line.startsWith("a=rtpmap:", ignoreCase = true) ||
                    line.startsWith("a=curr:qos", ignoreCase = true) ||
                    line.startsWith("a=des:qos", ignoreCase = true) ||
                    line.startsWith("a=sendrecv", ignoreCase = true) ||
                    line.startsWith("a=inactive", ignoreCase = true)
            }
            .joinToString("|")
            .take(1024)
        return "method=${request.method} headers=$headerNames " +
            "bodyBytes=${request.body.size} sdp=$sdpShape"
    }
}
