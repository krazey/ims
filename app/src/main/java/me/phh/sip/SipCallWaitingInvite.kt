// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

internal data class SipCallWaitingInviteInfo(
    val incomingCallId: String,
    val contentType: String,
    val hasCommunicationWaitingContentType: Boolean,
    val hasCommunicationWaitingBody: Boolean,
    val hasMidCallFeatureTag: Boolean,
    val activeCallId: String?,
    val activeCallOutgoing: Boolean?,
    val pendingOutgoingCallId: String?,
) {
    val hasActiveDifferentCall: Boolean
        get() = activeCallId != null && activeCallId != incomingCallId

    val hasPendingDifferentOutgoingCall: Boolean
        get() = pendingOutgoingCallId != null && pendingOutgoingCallId != incomingCallId

    val isInviteWhileBusy: Boolean
        get() = hasActiveDifferentCall || hasPendingDifferentOutgoingCall

    val looksLikeCarrierCallWaiting: Boolean
        get() = isInviteWhileBusy &&
            (hasCommunicationWaitingContentType || hasCommunicationWaitingBody || hasMidCallFeatureTag)

    fun logSummary(): String {
        val activeDirection = when (activeCallOutgoing) {
            true -> "outgoing"
            false -> "incoming"
            null -> "none"
        }
        return "callId=$incomingCallId contentType=${contentType.ifBlank { "<none>" }} " +
            "cwContentType=$hasCommunicationWaitingContentType " +
            "cwBody=$hasCommunicationWaitingBody midCallFeatureTag=$hasMidCallFeatureTag " +
            "activeCallId=$activeCallId activeDirection=$activeDirection " +
            "pendingOutgoingCallId=$pendingOutgoingCallId"
    }
}

internal object SipCallWaitingInviteClassifier {
    fun classify(
        request: SipRequest,
        incomingCallId: String,
        activeCallId: String?,
        activeCallOutgoing: Boolean?,
        pendingOutgoingCallId: String?,
    ): SipCallWaitingInviteInfo {
        val contentType = request.headers["content-type"]?.getOrNull(0).orEmpty()
        val normalizedContentType = contentType
            .substringBefore(';')
            .trim()
            .lowercase()
        val bodyText = String(request.body, Charsets.UTF_8)
        val hasCommunicationWaitingContentType =
            normalizedContentType == "application/vnd.3gpp.cw+xml"
        val hasCommunicationWaitingBody =
            bodyText.contains("communication-waiting-indication", ignoreCase = true)
        val hasMidCallFeatureTag = request.headers.values
            .asSequence()
            .flatten()
            .any { it.contains("+g.3gpp.mid-call", ignoreCase = true) }

        return SipCallWaitingInviteInfo(
            incomingCallId = incomingCallId,
            contentType = contentType,
            hasCommunicationWaitingContentType = hasCommunicationWaitingContentType,
            hasCommunicationWaitingBody = hasCommunicationWaitingBody,
            hasMidCallFeatureTag = hasMidCallFeatureTag,
            activeCallId = activeCallId,
            activeCallOutgoing = activeCallOutgoing,
            pendingOutgoingCallId = pendingOutgoingCallId,
        )
    }
}
