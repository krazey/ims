package me.phh.sip
import java.io.OutputStream

import android.telephony.Rlog
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress

internal data class IncomingInviteSdpAnswer(
    val reliableSequence: Int,
    val body: ByteArray,
)

internal object SipIncomingInviteSdpAnswerBuilder {
    fun build(
        owner: String,
        rtpSocket: DatagramSocket,
        sdpBandwidthAs: Int,
        allTracks: List<Int>,
        amrTrackDesc: String,
        remoteMaxptime: String,
        dtmfTrackDesc: String,
        amrFmtpAnswer: String,
        dtmfTrack: Int,
        callerSupportsPrecondition: Boolean,
        sendReliable183: Boolean,
        incomingCallId: String,
        reliableSequence: Int,
        localAddr: InetAddress,
        logTag: String,
    ): IncomingInviteSdpAnswer {
        val mySeqCounter = reliableSequence
        val ipType = if(localAddr is Inet6Address) "IP6" else "IP4"
        val sdpLines = mutableListOf(
            "v=0",
            "o=$owner 1 2 IN $ipType ${localAddr.hostAddress}",
            "s=phh voice call",
            "c=IN $ipType ${localAddr.hostAddress}",
            "b=AS:$sdpBandwidthAs",
            "b=RS:0",
            "b=RR:0",
            "t=0 0",
            "m=audio ${rtpSocket.localPort} RTP/AVP ${allTracks.joinToString(" ")}",
            "b=AS:$sdpBandwidthAs",
            "b=RS:0",
            "b=RR:0",
            "a=$amrTrackDesc",
            "a=ptime:20",
            "a=$remoteMaxptime",
            "a=$dtmfTrackDesc",
            "a=$amrFmtpAnswer",
            "a=fmtp:$dtmfTrack 0-15"
        )
        if (callerSupportsPrecondition) {
            val incomingCurrentQos = if (sendReliable183) "none" else "sendrecv"
            Rlog.d(
                logTag,
                "Incoming precondition SDP answer: callId=$incomingCallId " +
                    "sendReliable183=$sendReliable183 curr=$incomingCurrentQos",
            )
            sdpLines += listOf(
                "a=curr:qos local $incomingCurrentQos",
                "a=curr:qos remote $incomingCurrentQos",
                "a=des:qos mandatory local sendrecv",
                "a=des:qos mandatory remote sendrecv",
                "a=conf:qos remote sendrecv"
            )
        }
        sdpLines += "a=sendrecv"
        /*
         * Keep generated incoming SDP bodies strictly CRLF-framed, including
         * the final line terminator. Some IMS SBCs reject/tear down the call
         * after the 200 OK when the SDP body lacks the trailing CRLF.
         */
        val mySdp = (sdpLines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.US_ASCII)

        return IncomingInviteSdpAnswer(
            reliableSequence = mySeqCounter,
            body = mySdp,
        )
    }
}

internal object SipIncomingInviteProvisionalHeaders {
    fun build(
        request: SipRequest,
        commonHeaders: Map<String, List<String>>,
        dialogContact: String,
        callerSupportsPrecondition: Boolean,
        reliableSequence: Int,
        toWithTag: List<String>,
    ): Map<String, List<String>> {
        return commonHeaders + //Require: precondition
            """
                    Contact: $dialogContact
                    Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, INFO, MESSAGE, PRACK, OPTIONS
                    Content-Type: application/sdp
                    Require: 100rel${if (callerSupportsPrecondition) ", precondition" else ""}
                    RSeq: $reliableSequence
                    """.toSipHeadersMap() +
                        request.headers.filter { (k, _) -> k in listOf("cseq", "via", "from", "to", "call-id", "record-route") } +
                        mapOf("to" to toWithTag) -
            "route" - "security-verify"
    }
}

internal object SipIncomingInviteToHeaderTagger {
    fun tag(
        request: SipRequest,
        localToTag: String,
        logTag: String,
    ): List<String> {
        // Generate a single local tag for all responses in this dialog (RFC 3261 §12.1.1).
        // Important for tel: URIs: without <> the appended ;tag can be parsed as a TEL URI
        // parameter instead of a SIP To header parameter, and the network may ignore our 200 OK.
        val toWithTag = request.headers["to"]!!.map { h -> SipHeaderTagger.addTag(h, localToTag) }
        Rlog.d(logTag, "Incoming To header normalized and tagged")
        return toWithTag
    }
}


internal data class IncomingInviteFinalResponseWrite(
    val responseWriter: OutputStream,
    val responseBytes: ByteArray,
)

internal object SipIncomingInviteFinalSdp {
    fun completePreconditionAnswer(
        logTag: String,
        answerSdp: ByteArray,
        callId: String,
    ): ByteArray {
        val lines = answerSdp
            .toString(Charsets.UTF_8)
            .split("[\r\n]+".toRegex())
            .filter { it.isNotBlank() }

        val hasPrecondition = lines.any { line ->
            line.startsWith("a=curr:qos", ignoreCase = true) ||
                line.startsWith("a=des:qos", ignoreCase = true) ||
                line.startsWith("a=conf:qos", ignoreCase = true)
        }
        if (!hasPrecondition) return answerSdp

        val rewritten = lines.map { line ->
            when {
                line.startsWith("a=curr:qos local", ignoreCase = true) -> "a=curr:qos local sendrecv"
                line.startsWith("a=curr:qos remote", ignoreCase = true) -> "a=curr:qos remote sendrecv"
                line.startsWith("a=des:qos optional local", ignoreCase = true) -> "a=des:qos mandatory local sendrecv"
                line.startsWith("a=des:qos optional remote", ignoreCase = true) -> "a=des:qos mandatory remote sendrecv"
                line.startsWith("a=des:qos mandatory local", ignoreCase = true) -> "a=des:qos mandatory local sendrecv"
                line.startsWith("a=des:qos mandatory remote", ignoreCase = true) -> "a=des:qos mandatory remote sendrecv"
                line.startsWith("a=conf:qos remote", ignoreCase = true) -> "a=conf:qos remote sendrecv"
                line.equals("a=inactive", ignoreCase = true) -> "a=sendrecv"
                line.equals("a=sendonly", ignoreCase = true) -> "a=sendrecv"
                line.equals("a=recvonly", ignoreCase = true) -> "a=sendrecv"
                else -> line
            }
        }.let { mapped ->
            val withConf = if (mapped.any { it.startsWith("a=conf:qos remote", ignoreCase = true) }) {
                mapped
            } else {
                mapped + "a=conf:qos remote sendrecv"
            }
            if (withConf.any { it.equals("a=sendrecv", ignoreCase = true) }) {
                withConf
            } else {
                withConf + "a=sendrecv"
            }
        }

        if (rewritten != lines) {
            Rlog.d(logTag, "Completing incoming final 200 OK precondition SDP: callId=$callId")
        }
        return (rewritten.joinToString("\r\n") + "\r\n").toByteArray(Charsets.US_ASCII)
    }
}

internal enum class IncomingAcceptMediaPrewarmAction {
    START,
    ALREADY_STARTED,
}

internal object SipIncomingInviteFinalResponses {
    private const val TAG = "PHH SipIncoming"



    fun initialFinalResponseRetransmitDelayMs(): Long = 500L

    fun maxFinalResponseRetransmitElapsedMs(): Long = 32_000L

    fun nextFinalResponseRetransmitDelayMs(currentDelayMs: Long): Long =
        (currentDelayMs * 2).coerceAtMost(4_000L)








    fun acceptWithoutValidIncomingCallLog(call: Any?): String =
        "acceptCall without valid incoming currentCall: $call"

    fun acceptAbortedAfterAccessGuardLog(
        acceptedCallId: String,
        currentCallId: String?,
        outgoing: Boolean?,
    ): String =
        "acceptCall aborted after IMS access guard because current call changed: " +
            "acceptedCallId=$acceptedCallId current=$currentCallId outgoing=$outgoing"

    fun finalResponseMissingAckStopReason(): String = "call cleanup"

    fun finalResponseMissingAckTerminationReason(): String = "incoming ACK timeout"

    fun finalResponseMissingAckCancellationExtras(acceptedCallId: String): Map<String, String> =
        mapOf("call-id" to acceptedCallId)

    fun finalResponseRetransmitLog(
        acceptedCallId: String,
        elapsedMs: Long,
    ): String =
        "Retransmitting incoming 200 OK waiting for ACK callId=$acceptedCallId elapsed=${elapsedMs}ms"

    fun finalResponseRetransmitWriteContext(
        acceptedCallId: String,
        elapsedMs: Long,
    ): String =
        "incoming INVITE final 200 OK retransmit callId=$acceptedCallId elapsed=${elapsedMs}ms"

    fun finalResponseRetransmitWriteFailureLog(
        acceptedCallId: String,
        elapsedMs: Long,
    ): String =
        "Stopping incoming 200 OK retransmit after write failure callId=$acceptedCallId elapsed=${elapsedMs}ms"

    fun finalResponseMissingAckTimeoutLog(
        acceptedCallId: String,
        elapsedMs: Long,
    ): String =
        "Incoming accepted call still has no ACK after ${elapsedMs}ms; clearing pending accepted state callId=$acceptedCallId"

    fun duplicateFinalResponseResendLog(
        incomingCallId: String,
        incomingCseq: String,
        responseBytesSize: Int,
    ): String =
        "Re-sending final 200 OK on duplicate incoming INVITE transaction: " +
            "callId=$incomingCallId cseq=$incomingCseq bytes=$responseBytesSize"

    fun duplicateFinalResponseWriteContext(
        incomingCallId: String,
        incomingCseq: String,
    ): String =
        "duplicate incoming INVITE final 200 OK callId=$incomingCallId cseq=$incomingCseq"

    fun duplicateFinalResponseFailureLog(
        incomingCallId: String,
        incomingCseq: String,
    ): String =
        "Failed to send final 200 OK on duplicate incoming INVITE transaction: " +
            "callId=$incomingCallId cseq=$incomingCseq"


    fun acceptedFinalResponseWriteFailureCancellationExtras(acceptedCallId: String): Map<String, String> =
        mapOf("call-id" to acceptedCallId)

    fun acceptedFinalResponseWriteLog(
        response: SipResponse,
        hasIncomingResponseWriter: Boolean,
    ): String =
        "Sending ${response.safeLogSummary()} via incomingResponseWriter=$hasIncomingResponseWriter"

    fun acceptedFinalResponseWriteContext(acceptedCallId: String): String =
        "incoming INVITE final 200 OK callId=$acceptedCallId"

    fun shouldOmitFinalSdp(hasEarlyMedia: Boolean): Boolean = hasEarlyMedia

    fun omitFinalSdpLogMessage(acceptedCallId: String): String =
        "Omitting SDP from final incoming 200 OK because reliable provisional/UPDATE offer-answer already completed " +
            "callId=$acceptedCallId"


    fun incomingAckAudioRouteSettleReason(): String =
        "incoming ACK audio route settle"

    fun mediaPrewarmAction(startedNow: Boolean): IncomingAcceptMediaPrewarmAction =
        if (startedNow) {
            IncomingAcceptMediaPrewarmAction.START
        } else {
            IncomingAcceptMediaPrewarmAction.ALREADY_STARTED
        }

    fun mediaPrewarmLogMessage(action: IncomingAcceptMediaPrewarmAction): String =
        when (action) {
            IncomingAcceptMediaPrewarmAction.START ->
                "Prewarming incoming media threads after final 200 OK while waiting for ACK; delaying mic open after ACK"
            IncomingAcceptMediaPrewarmAction.ALREADY_STARTED ->
                "Incoming media threads already started while accepting call"
        }




    fun rejectWithoutValidIncomingCallLog(call: Any?): String =
        "rejectCall without valid incoming currentCall: $call"

    fun localRejectTerminationReason(): String = "local reject"

    fun localRejectWriteLog(
        response: SipResponse,
        hasIncomingResponseWriter: Boolean,
    ): String =
        "Sending ${response.safeLogSummary()} via incomingResponseWriter=$hasIncomingResponseWriter"

    fun localRejectCleanupReason(): String = "call cleanup"

    fun localRejectCancellationExtras(rejectedCallId: String): Map<String, String> =
        mapOf(
            "call-id" to rejectedCallId,
            "statusCode" to "603",
            "statusString" to "Decline",
            "localReject" to "true",
        )

    fun localRejectResponse(callHeaders: Map<String, List<String>>): SipResponse {
        val responseHeaders = callHeaders - "rseq" - "require" - "content-type" - "p-access-network-info" +
            "Content-Length: 0".toSipHeadersMap()

        return SipResponse(
            statusCode = 603,
            statusString = "Decline",
            headersParam = responseHeaders,
            autofill = false,
        )
    }

    fun acceptedFinalResponse(
        callHeaders: Map<String, List<String>>,
        contact: String,
        body: ByteArray,
        omitFinalSdp: Boolean,
    ): SipResponse {
        val finalSdpHeaders = if (!omitFinalSdp) {
            """
            Content-Type: application/sdp
            Content-Length: ${body.size}
            """.toSipHeadersMap()
        } else {
            "Content-Length: 0".toSipHeadersMap()
        }
        val sessionTimerHeaders =
            SipSessionTimerNegotiation.responseHeadersForIncomingRequest(callHeaders, TAG)
        val responseHeaders =
            callHeaders - "rseq" - "security-verify" - "p-access-network-info" - "content-type" - "content-length" -
                "session-expires" - "min-se" - "require" +
                "Contact: $contact".toSipHeadersMap() +
                sessionTimerHeaders +
                finalSdpHeaders

        return SipResponse(
            statusCode = 200,
            statusString = "OK",
            headersParam = responseHeaders,
            body = body,
            autofill = false
        )
    }

    fun duplicateFinalResponse(
        callHeaders: Map<String, List<String>>,
        contact: String,
        body: ByteArray,
        omitFinalSdp: Boolean,
    ): SipResponse {
        val finalSdpHeaders = if (!omitFinalSdp) {
            (
                "Content-Type: application/sdp\n" +
                    "Content-Length: ${body.size}\n"
            ).toSipHeadersMap()
        } else {
            "Content-Length: 0".toSipHeadersMap()
        }
        val sessionTimerHeaders =
            SipSessionTimerNegotiation.responseHeadersForIncomingRequest(callHeaders, TAG)
        val finalHeaders =
            callHeaders -
                "rseq" -
                "security-verify" -
                "p-access-network-info" -
                "content-type" -
                "content-length" -
                "session-expires" -
                "min-se" -
                "require" +
                "Contact: $contact".toSipHeadersMap() +
                sessionTimerHeaders +
                finalSdpHeaders

        return SipResponse(
            statusCode = 200,
            statusString = "OK",
            headersParam = finalHeaders,
            body = body,
            autofill = false,
        )
    }

}
