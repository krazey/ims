package me.phh.sip

import android.telephony.Rlog

internal data class OutgoingFinalInviteAckRequest(
    val request: SipRequest,
    val inviteCseq: Int,
)

internal object SipOutgoingInviteFinalAck {
    fun buildAckRequest(
        response: SipResponse,
        myHeaders: Map<String, List<String>>,
        fallbackTarget: String,
        extractDestinationFromContact: (String) -> String,
    ): OutgoingFinalInviteAckRequest {
        // ACK C-Seq must be the same as INVITE C-Seq.
        val cseqLine = response.headers["cseq"]!![0]
        val cseq = cseqLine.split(" ")[0].toInt()
        val newTo = response.headers["to"]!![0]
        val newFrom = response.headers["from"]!![0]
        // ACK to 2xx must be sent to the Contact from the response (RFC 3261 §13.2.2.4).
        val ackTo = response.headers["contact"]?.get(0)
            ?.let { extractDestinationFromContact(it) } ?: fallbackTarget
        // ACK is a dialog request; route set comes from Record-Route in the 200 OK
        // (RFC 3261 §12.1.2), not from the registration Service-Route in myHeaders.
        val dialogRoute = outgoingDialogRouteSet(response.headers)
        val dialogHeaders = myHeaders - "route" - "record-route"
        val ackHeaders = if (dialogRoute.isNotEmpty()) {
            dialogHeaders + ("route" to dialogRoute)
        } else {
            dialogHeaders
        }
        val request =
            SipRequest(
                SipMethod.ACK,
                ackTo,
                ackHeaders - "content-type" + """
                    CSeq: $cseq ACK
                    To: $newTo
                    From: $newFrom
                    """.toSipHeadersMap()
            )

        return OutgoingFinalInviteAckRequest(
            request = request,
            inviteCseq = cseq,
        )
    }
}

internal data class OutgoingFinalInviteAckState(
    val responseCseq: String,
    val finalInviteCallId: String,
    val finalInviteAfterLocalCancel: Boolean,
    val finalInviteHasSdp: Boolean,
)

internal object SipOutgoingInviteFinalAckState {

    fun finalInviteAfterLocalCancelWithoutSdpLog(callId: String): String =
        "Confirmed outgoing dialog after local CANCEL without final SDP; sending BYE immediately callId=$callId"

    fun finalAnswerWithoutSdpAfterLocalCancelReason(): String =
        "final answer without SDP after local CANCEL"

    fun finalInviteAnswerWithoutSdpReason(): String =
        "final INVITE answer without SDP"

    fun finalInviteAnswerClearsEarlyMediaGateLog(callId: String): String =
        "Final outgoing answer received; clearing early-media RTP gate until post-answer RTP arrives callId=$callId"

    fun finalInviteAnswerConnectedReason(): String =
        "final INVITE answer"

    fun finalInviteAnswerCurrentCallMissingLog(callId: String): String =
        "Final INVITE answer but currentCall is null after ACK callId=$callId"


    fun sendingFinalInviteAckLog(request: SipRequest): String =
        "Sending ${request.safeLogSummary()}"


    fun outgoingConfirmedDialogAfterAckLog(
        remoteTarget: String,
        nextLocalCseq: Int,
        routeHeader: List<String>?,
    ): String =
        "Outgoing confirmed dialog after ACK: " +
            "remoteTarget=$remoteTarget " +
            "nextLocalCseq=$nextLocalCseq " +
            "route=$routeHeader"

    fun fromResponse(
        logTag: String,
        response: SipResponse,
        finalInviteAfterLocalCancel: Boolean,
    ): OutgoingFinalInviteAckState? {
        val responseCseq = response.headers["cseq"]?.getOrNull(0).orEmpty()
        if (!responseCseq.contains("INVITE") || (response.statusCode != 200 && response.statusCode != 202)) {
            return null
        }

        val finalInviteCallId = response.callIdOrEmpty()
        val finalInviteHasSdp =
            response.headers["content-type"]?.getOrNull(0) == "application/sdp"

        if (finalInviteAfterLocalCancel) {
            Rlog.w(
                logTag,
                "Final INVITE answer arrived after local CANCEL; " +
                    "ACK first, then BYE once dialog state exists callId=$finalInviteCallId",
            )
        }

        return OutgoingFinalInviteAckState(
            responseCseq = responseCseq,
            finalInviteCallId = finalInviteCallId,
            finalInviteAfterLocalCancel = finalInviteAfterLocalCancel,
            finalInviteHasSdp = finalInviteHasSdp,
        )
    }
}
