// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

/** A diagnostic SIP description that excludes URIs, identities, auth, and body data. */
internal fun SipMessage.safeLogSummary(): String {
    val kind = when (this) {
        is SipRequest -> "request=$method"
        is SipResponse -> "response=$statusCode"
        else -> "message"
    }
    val cseq = headers["cseq"]?.firstOrNull().orEmpty()
    val callIdToken = headers["call-id"]?.firstOrNull()
        ?.hashCode()
        ?.let(Integer::toHexString)
        .orEmpty()
    val contentType = headers["content-type"]?.firstOrNull()
        ?.substringBefore(';')
        ?.trim()
        .orEmpty()
    return "$kind cseq=$cseq callIdHash=$callIdToken " +
        "contentType=$contentType bodyBytes=${body.size}"
}
