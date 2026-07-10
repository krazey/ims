// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.Rlog

/**
 * RFC 4028 session-timer role negotiation.
 *
 * Keep timer header policy separate from SipHandler. PhhIms currently accepts
 * peer refreshes but does not originate periodic refreshes, so prefer the peer
 * as refresher whenever the initial UAC left the role open.
 */
internal data class SipSessionTimerSelection(
    val intervalSeconds: Int,
    val refresher: String,
)

internal object SipSessionTimerNegotiation {
    private const val MIN_INTERVAL_SECONDS = 90

    fun outgoingRequestValue(intervalSeconds: Int): String =
        "${intervalSeconds.coerceAtLeast(MIN_INTERVAL_SECONDS)};refresher=uas"

    fun selectionFromHeaders(
        headers: SipHeadersMap,
        defaultRefresher: String? = null,
    ): SipSessionTimerSelection? {
        val rawSessionExpires = sessionExpiresHeader(headers)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val interval = rawSessionExpires
            .substringBefore(';')
            .trim()
            .toIntOrNull()
            ?.takeIf { it >= MIN_INTERVAL_SECONDS }
            ?: return null
        val refresher = parameter(rawSessionExpires, "refresher")
            ?.takeIf { it == "uac" || it == "uas" }
            ?: defaultRefresher
                ?.lowercase()
                ?.takeIf { it == "uac" || it == "uas" }
            ?: return null
        return SipSessionTimerSelection(
            intervalSeconds = interval,
            refresher = refresher,
        )
    }

    fun minimumIntervalFromResponse(headers: SipHeadersMap): Int? =
        firstHeaders(headers, listOf("min-se"))
            .firstOrNull()
            ?.substringBefore(';')
            ?.trim()
            ?.toIntOrNull()
            ?.coerceAtLeast(MIN_INTERVAL_SECONDS)

    fun rejectionResponseForIncomingRequest(
        request: SipRequest,
        logTag: String,
    ): SipResponse? {
        val rawSessionExpires = sessionExpiresHeader(request.headers) ?: return null
        val interval = rawSessionExpires
            .substringBefore(';')
            .trim()
            .toIntOrNull()
            ?: return null
        if (interval >= MIN_INTERVAL_SECONDS) return null

        Rlog.w(
            logTag,
            "Rejecting Session-Expires below minimum: " +
                "value=$rawSessionExpires minimum=$MIN_INTERVAL_SECONDS",
        )
        val responseHeaders = request.headers.filter { (name, _) ->
            name in setOf("cseq", "via", "from", "to", "call-id")
        }.toMutableMap()
        request.headers["to"]?.let { toHeaders ->
            val localTag = randomBytes(6).toHex()
            responseHeaders["to"] = toHeaders.map { header ->
                if (header.contains(";tag=", ignoreCase = true)) {
                    header
                } else {
                    SipHeaderTagger.addTag(header, localTag)
                }
            }
        }
        responseHeaders["min-se"] = listOf(MIN_INTERVAL_SECONDS.toString())
        return SipResponse(
            statusCode = 422,
            statusString = "Session Interval Too Small",
            headersParam = responseHeaders,
        )
    }

    fun responseHeadersForIncomingRequest(
        requestHeaders: SipHeadersMap,
        logTag: String,
    ): SipHeadersMap {
        val rawSessionExpires = sessionExpiresHeader(requestHeaders)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyMap()
        val interval = rawSessionExpires
            .substringBefore(';')
            .trim()
            .toIntOrNull()
            ?.takeIf { it >= MIN_INTERVAL_SECONDS }
            ?: run {
                Rlog.w(logTag, "Ignoring invalid Session-Expires value: $rawSessionExpires")
                return emptyMap()
            }
        val requestedRefresher = parameter(rawSessionExpires, "refresher")
        val peerSupportsTimer = headerContainsToken(
            headers = requestHeaders,
            names = listOf("supported", "k"),
            token = "timer",
        )
        val responseRefresher = when {
            requestedRefresher == "uac" -> "uac"
            requestedRefresher == "uas" -> "uas"
            peerSupportsTimer -> "uac"
            else -> "uas"
        }

        if (responseRefresher == "uas") {
            Rlog.w(
                logTag,
                "Peer selected local session refresher; periodic local refresh is not yet supported",
            )
        }

        val requireTimer = responseRefresher == "uac" || peerSupportsTimer
        val headers = mutableMapOf<String, List<String>>(
            "session-expires" to listOf("$interval;refresher=$responseRefresher"),
        )
        if (requireTimer) {
            headers["require"] = listOf("timer")
        }
        return headers
    }

    private fun parameter(value: String, name: String): String? =
        value
            .split(';')
            .asSequence()
            .drop(1)
            .map { it.trim() }
            .firstOrNull { it.substringBefore('=').trim().equals(name, ignoreCase = true) }
            ?.substringAfter('=', "")
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }

    private fun sessionExpiresHeader(headers: SipHeadersMap): String? =
        firstHeaders(headers, listOf("session-expires", "x")).firstOrNull()

    private fun headerContainsToken(
        headers: SipHeadersMap,
        names: List<String>,
        token: String,
    ): Boolean = firstHeaders(headers, names)
        .flatMap { it.split(',') }
        .any { it.trim().equals(token, ignoreCase = true) }

    private fun firstHeaders(
        headers: SipHeadersMap,
        names: List<String>,
    ): List<String> {
        for (name in names) {
            headers[name]?.let { return it }
            headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.let { return it }
        }
        return emptyList()
    }
}
