package me.phh.sip

import java.io.OutputStream

internal object SipRemoteDialogTermination {

    fun localDialogRequestWriter(
        incomingResponseWriter: OutputStream?,
        registeredDialogWriter: OutputStream?,
        fallbackWriter: () -> OutputStream,
    ): OutputStream =
        incomingResponseWriter ?: registeredDialogWriter ?: fallbackWriter()





    fun deferredLocalByeAfterAckLog(): String =
        "ACK received after local pre-ACK hangup; sending deferred BYE"

    fun deferredLocalByeAfterAckReason(): String =
        "deferred local BYE after ACK"


    fun clearingPendingOutgoingInviteLog(
        callId: String,
        closeRtpSocket: Boolean,
        reason: String,
    ): String =
        "Clearing pending outgoing INVITE callId=$callId closeRtpSocket=$closeRtpSocket reason=$reason"

    fun closingPendingOutgoingRtpSocketFailedLog(): String =
        "Closing pending outgoing RTP socket failed"

    fun pendingCancelAlreadySentLog(callId: String, reason: String): String =
        "CANCEL already sent for pending outgoing INVITE callId=$callId reason=$reason"

    fun inviteCseqNumber(headers: SipHeadersMap): String =
        headers["cseq"]?.getOrNull(0)?.substringBefore(" ") ?: "1"

    fun cancellableCancelHeaders(headers: SipHeadersMap): SipHeadersMap =
        headers.filter { (key, _) ->
            key in setOf(
                "via",
                "route",
                "from",
                "to",
                "call-id",
                "max-forwards",
                "user-agent",
                "p-access-network-info",
                "security-verify",
                "require",
                "proxy-require",
            )
        }

    fun cancelHeaders(
        cancellableHeaders: SipHeadersMap,
        inviteCseqNumber: String,
    ): SipHeadersMap =
        cancellableHeaders - "cseq" - "content-length" - "content-type" +
            """
            CSeq: $inviteCseqNumber CANCEL
            Content-Length: 0
            """.toSipHeadersMap()

    fun cancelRequest(
        destination: String,
        cancelHeaders: SipHeadersMap,
    ): SipRequest =
        SipRequest(
            SipMethod.CANCEL,
            destination,
            headersParam = cancelHeaders,
        )

    fun pendingCancelSendLog(
        callId: String,
        reason: String,
        cancel: SipRequest,
    ): String =
        "Sending CANCEL for pending outgoing INVITE callId=$callId reason=$reason $cancel"

    fun pendingCancelWriteLabel(): String = "SipHandler cancel"

    fun localCancelSentReason(reason: String): String =
        "local CANCEL sent: $reason"

    fun pendingOutgoingHangupLog(callId: String): String =
        "Local hangup while outgoing INVITE is still pending; sending CANCEL callId=$callId"

    fun localTerminateReason(): String = "local terminate"

    fun localHangupBeforeDialogReason(): String = "local hangup before dialog"

    fun localCancelExtras(callId: String): Map<String, String> =
        mapOf(
            "call-id" to callId,
            "statusString" to "Local hangup",
            "outgoingCall" to "true",
            "localHangup" to "true",
        )

    fun terminateWithoutCallLog(): String =
        "terminateCall without currentCall or pending outgoing INVITE"

    fun localHangupBeforeFinalAnswerLog(callId: String): String =
        "Local hangup before outgoing INVITE final answer; sending CANCEL callId=$callId"

    fun localHangupBeforeFinalAnswerReason(): String =
        "local hangup before final INVITE answer"

    fun outgoingUnconfirmedNoPendingInviteLog(): String =
        "Outgoing call not confirmed yet but no pending INVITE exists; falling back to BYE"

    fun incomingPreAckHangupLog(): String =
        "Local hangup before incoming ACK; deferring BYE until ACK and keeping 200 OK retransmission active"

    fun incomingPreAckKeepaliveLog(): String =
        "Keeping accepted pre-ACK incoming Call-ID live for final 200 OK retransmits"

    fun localByeTerminationReason(): String = "local BYE"

    fun outgoingByeWaitLog(callId: String?): String =
        "Keeping outgoing dialog until BYE transaction completes callId=$callId"

    fun outgoingByeTimeoutLog(callId: String?): String =
        "Clearing outgoing dialog after BYE response timeout callId=$callId"

    fun confirmedCallTerminatedReason(): String = "confirmed call terminated"

    fun localHangupCancellationExtras(): Map<String, String> = emptyMap()

    fun byeRequest(
        remoteContact: String,
        byeHeaders: SipHeadersMap,
    ): SipRequest =
        SipRequest(
            SipMethod.BYE,
            remoteContact,
            headersParam = byeHeaders,
        )

    fun byeLog(request: SipRequest): String =
        "Sending ${request.safeLogSummary()}"

    fun byeWriteLabel(): String = "SipHandler bye"

    fun cleanupReason(): String = "call cleanup"

    fun cancelledCallLog(callId: String, method: SipMethod): String =
        "Cancelled call $callId method=$method"

    fun remoteCancelTerminationReason(): String = "remote CANCEL"

    fun remoteMethodReason(method: SipMethod): String = "remote $method"

    fun remoteCancelCancellationExtras(callId: String): Map<String, String> =
        mapOf("call-id" to callId)

    fun unexpectedNonByeDialogTerminationLog(method: SipMethod): String =
        "handleCancel called for unexpected method $method"

    fun remoteEndExtras(
        logTag: String,
        callId: String,
        isBye: Boolean,
        isOutgoingCall: Boolean,
        outgoingConnectedNotified: Boolean,
    ): Map<String, String> =
        SipRemoteEndExtrasBuilder.build(
            logTag = logTag,
            callId = callId,
            isBye = isBye,
            isOutgoingCall = isOutgoingCall,
            outgoingConnectedNotified = outgoingConnectedNotified,
        )

    fun lateCancelReceivedAfterFinalResponseLog(): String =
        "CANCEL received after final 200 OK was sent — replying 200 to CANCEL and keeping answered dialog"

    fun lateCancelOkResponse(
        request: SipRequest,
        toOverride: List<String>?,
    ): SipResponse {
        val responseHeaders = SipDialogHeaderBuilder.responseHeadersFromRequest(
            request,
            toOverride = toOverride,
            extra = SipRemoteTerminationResponses.emptyBodyHeaders(),
        )
        return SipRemoteTerminationResponses.ok(responseHeaders)
    }

    fun lateCancelOkLog(response: SipResponse): String =
        "Sending explicit 200 OK to late CANCEL: ${response.safeLogSummary()}"

    fun cancelOkResponse(
        request: SipRequest,
        toOverride: List<String>?,
    ): SipResponse {
        val cancelOkHeaders = SipDialogHeaderBuilder.responseHeadersFromRequest(
            request,
            toOverride = toOverride,
            extra = SipRemoteTerminationResponses.emptyBodyHeaders(),
        )
        return SipRemoteTerminationResponses.ok(cancelOkHeaders)
    }

    fun cancelOkLog(response: SipResponse): String =
        "Sending 200 OK to CANCEL ${response.safeLogSummary()}"

    fun cancelledInviteResponse(
        request: SipRequest,
        toOverride: List<String>?,
    ): SipResponse {
        val originalInviteCseq = SipRemoteTerminationResponses.inviteCseqFromCancel(request)
        val inviteTerminatedHeaders = SipDialogHeaderBuilder.responseHeadersFromRequest(
            request,
            toOverride = toOverride,
            extra = SipRemoteTerminationResponses.cancelledInviteHeaders(originalInviteCseq),
        )
        return SipRemoteTerminationResponses.requestTerminated(inviteTerminatedHeaders)
    }

    fun cancelledInviteLog(response: SipResponse): String =
        "Sending 487 for cancelled INVITE ${response.safeLogSummary()}"

    fun writeResponse(
        responseWriter: OutputStream,
        response: SipResponse,
    ) {
        SipMessageWriter.write(
            responseWriter,
            response.toByteArray(),
            "remote dialog termination response ${response.statusCode}",
        )
    }
}
