package me.phh.sip

import android.telephony.Rlog
import java.net.DatagramSocket
import java.net.InetAddress

internal data class IncomingInviteDialogSetupState(
    val rtpSocket: DatagramSocket,
    val reliableSequence: Int,
    val sdp: ByteArray,
    val headers: Map<String, List<String>>,
    val plainRingingAlreadySent: Boolean = false,
)

internal data class IncomingInviteRejectDecision(
    val statusCode: Int,
    val terminatedReason: String,
)

internal data class IncomingDialogInstallAbortDecision(
    val message: String,
    val clearCurrentCall: Boolean,
)

internal object SipIncomingInviteDialogSetup {









    fun incomingCallNotificationExtras(
        incomingCallId: String,
        selectedAudioCodec: NegotiatedAudioCodec,
    ): Map<String, String> =
        mapOf("call-id" to incomingCallId) + SipAudioCodecNegotiator.audioCodecExtras(selectedAudioCodec)

    fun installAbortDecision(
        incomingCallId: String,
        wasRecentlyTerminated: Boolean,
        installedIncomingCallId: String,
        installedStillCurrent: Boolean,
    ): IncomingDialogInstallAbortDecision? {
        if (!wasRecentlyTerminated && installedIncomingCallId == incomingCallId && installedStillCurrent) {
            return null
        }

        return IncomingDialogInstallAbortDecision(
            message = "Aborting incoming ringing because Call-ID was terminated during setup: " +
                "callId=$incomingCallId installed=$installedIncomingCallId",
            clearCurrentCall = installedIncomingCallId == incomingCallId,
        )
    }

    fun buildDialogSetupState(
        request: SipRequest,
        incomingCallId: String,
        incomingOffer: IncomingInviteOffer,
        rtpSocket: DatagramSocket,
        dialogContact: String,
        commonHeaders: Map<String, List<String>>,
        reliableSequence: Int,
        localToTag: String,
        localAddr: InetAddress,
        logTag: String,
    ): IncomingInviteDialogSetupState {
        val incomingSdpAnswer = SipIncomingInviteSdpAnswerBuilder.build(
            owner = incomingOffer.owner,
            rtpSocket = rtpSocket,
            sdpBandwidthAs = incomingOffer.sdpBandwidthAs,
            allTracks = incomingOffer.allTracks,
            amrTrackDesc = incomingOffer.amrTrackDesc,
            remoteMaxptime = incomingOffer.remoteMaxptime,
            dtmfTrackDesc = incomingOffer.dtmfTrackDesc,
            amrFmtpAnswer = incomingOffer.amrFmtpAnswer,
            dtmfTrack = incomingOffer.dtmfTrack,
            callerSupportsPrecondition = incomingOffer.callerSupportsPrecondition,
            sendReliable183 = incomingOffer.sendReliable183,
            incomingCallId = incomingCallId,
            reliableSequence = reliableSequence,
            localAddr = localAddr,
            logTag = logTag,
        )
        val mySeqCounter = incomingSdpAnswer.reliableSequence
        val mySdp = incomingSdpAnswer.body

        val toWithTag = SipIncomingInviteToHeaderTagger.tag(
            request = request,
            localToTag = localToTag,
            logTag = logTag,
        )

        val myHeaders = SipIncomingInviteProvisionalHeaders.build(
            request = request,
            commonHeaders = commonHeaders,
            dialogContact = dialogContact,
            callerSupportsPrecondition = incomingOffer.callerSupportsPrecondition,
            reliableSequence = mySeqCounter,
            toWithTag = toWithTag,
        )

        return IncomingInviteDialogSetupState(
            rtpSocket = rtpSocket,
            reliableSequence = mySeqCounter,
            sdp = mySdp,
            headers = myHeaders,
        )
    }

    fun createIncomingRtpSocket(
        logTag: String,
        localAddr: InetAddress,
        rtpRemoteAddr: InetAddress,
        rtpRemotePort: String,
        bindSocket: (DatagramSocket) -> Unit,
        reconnectIms: (String) -> Unit,
    ): DatagramSocket? {
        val rtpSocket = try {
            DatagramSocket(0, localAddr)
        } catch (t: Throwable) {
            Rlog.e(logTag, "Failed to bind incoming RTP socket to $localAddr; IMS address is likely stale", t)
            reconnectIms("incoming RTP bind failed for localAddr=$localAddr")
            return null
        }
        try {
            bindSocket(rtpSocket)
            // Do not connect incoming RTP sockets. A connected DatagramSocket filters
            // received UDP packets to the connected remote address and port. Some IMS
            // media gateways send downlink RTP from a source port different from the
            // offered SDP m=audio port, while still accepting uplink RTP sent to the
            // offered port.
            //
            // Keep uplink sends explicit via RtpPacketSender and leave receive()
            // unrestricted to the actual media source on the IMS network.
            Rlog.d(
                logTag,
                "Incoming RTP socket left unconnected; txTarget=${rtpRemoteAddr}:${rtpRemotePort} " +
                    "local=${rtpSocket.localAddress}:${rtpSocket.localPort}",
            )
        } catch (t: Throwable) {
            Rlog.e(logTag, "Failed to bind incoming RTP socket", t)
            try { rtpSocket.close() } catch (_: Throwable) {}
            reconnectIms("incoming RTP bind failed")
            return null
        }
        Rlog.d(
            logTag,
            "RTP socket created: local=${rtpSocket.localAddress}:${rtpSocket.localPort}, " +
                "connected=${rtpSocket.isConnected}, socketRemote=${rtpSocket.inetAddress}:${rtpSocket.port}, " +
                "txTarget=${rtpRemoteAddr}:${rtpRemotePort}",
        )
        return rtpSocket
    }

    fun rejectRecentlyTerminatedInviteIfNeeded(
        logTag: String,
        incomingCallId: String,
        request: SipRequest,
        wasRecentlyTerminated: Boolean,
        currentCallId: String?,
        currentCallOutgoing: Boolean?,
        incomingAcceptedAwaitingAck: Boolean,
        incomingFinalResponseSent: Boolean,
        incomingHangupAfterAck: Boolean,
    ): Int? {
        if (!wasRecentlyTerminated) return null

        val incomingCseq = request.headers["cseq"]?.getOrNull(0).orEmpty()
        val isAcceptedPreAckCurrentCall =
            currentCallId == incomingCallId &&
                currentCallOutgoing == false &&
                (incomingAcceptedAwaitingAck || incomingFinalResponseSent) &&
                incomingHangupAfterAck

        if (!isAcceptedPreAckCurrentCall) {
            Rlog.w(logTag, "Rejecting duplicate incoming INVITE for recently terminated Call-ID: callId=$incomingCallId cseq=$incomingCseq")
            return 486
        }

        Rlog.w(
            logTag,
            "Allowing duplicate incoming INVITE for accepted pre-ACK call despite recently terminated marker: " +
                "callId=$incomingCallId cseq=$incomingCseq awaitingAck=$incomingAcceptedAwaitingAck",
        )
        return null
    }

    fun rejectWhileBusyOrOutgoingPending(
        logTag: String,
        request: SipRequest,
        incomingCallId: String,
        activeCallId: String?,
        activeCallOutgoing: Boolean?,
        pendingOutgoingCallId: String?,
    ): IncomingInviteRejectDecision? {
        if (activeCallId != null && activeCallId != incomingCallId) {
            val activeDirection = if (activeCallOutgoing == true) "outgoing" else "incoming"
            val incomingCseq = request.headers["cseq"]?.getOrNull(0).orEmpty()
            Rlog.w(
                logTag,
                "Rejecting second incoming INVITE while busy: " +
                    "callId=$incomingCallId cseq=$incomingCseq " +
                    "activeCallId=$activeCallId activeDirection=$activeDirection"
            )
            return IncomingInviteRejectDecision(
                statusCode = 486,
                terminatedReason = "busy reject",
            )
        }

        if (pendingOutgoingCallId != null && pendingOutgoingCallId != incomingCallId) {
            val incomingCseq = request.headers["cseq"]?.getOrNull(0).orEmpty()
            Rlog.w(
                logTag,
                "Rejecting incoming INVITE while outgoing INVITE is pending: " +
                    "callId=$incomingCallId cseq=$incomingCseq " +
                    "pendingOutgoingCallId=$pendingOutgoingCallId"
            )
            return IncomingInviteRejectDecision(
                statusCode = 486,
                terminatedReason = "outgoing pending reject",
            )
        }

        return null
    }

    fun explicitTryingResponse(request: SipRequest): SipResponse =
        SipResponse(
            statusCode = 100,
            statusString = "Trying",
            headersParam = SipDialogHeaderBuilder.responseHeadersFromRequest(
                request,
                extra = "Content-Length: 0".toSipHeadersMap(),
            ),
            autofill = false
        )

    fun reliableProvisionalResponse(
        headers: Map<String, List<String>>,
        sdp: ByteArray,
    ): SipResponse =
        SipResponse(
            statusCode = 183,
            statusString = "Session Progress",
            headersParam = headers,
            body = sdp
        )

    fun plainRingingResponse(
        headers: Map<String, List<String>>,
    ): SipResponse {
        val ringingHeaders = headers - "rseq" - "content-type" - "require" - "p-access-network-info" +
            """
Supported: replaces, timer
Content-Length: 0

""".toSipHeadersMap()
        return SipResponse(
            statusCode = 180,
            statusString = "Ringing",
            headersParam = ringingHeaders,
            autofill = false
        )
    }

    fun abortSetupIfTerminated(
        logTag: String,
        incomingCallId: String,
        rtpSocket: DatagramSocket,
        wasTerminated: Boolean,
    ): Boolean {
        if (!wasTerminated) return false

        Rlog.w(logTag, "Aborting incoming call setup because Call-ID was terminated before dialog install: callId=$incomingCallId")
        try { rtpSocket.close() } catch (t: Throwable) { Rlog.d(logTag, "Closing aborted incoming RTP socket failed", t) }
        return true
    }


    fun localDialogEndpoint(
        localHost: String?,
        isIpv6: Boolean,
        port: Int,
    ): String {
        val host = localHost.toString()
        return if (isIpv6) "[$host]:$port" else "$host:$port"
    }

    fun buildDialogContact(
        logTag: String,
        request: SipRequest,
        owner: String,
        incomingCallId: String,
        localEndpoint: String,
        fallbackTransport: String,
        imei: String,
    ): String {
        val incomingDialogTransport = request.headers["via"]
            ?.firstOrNull()
            ?.substringAfter("SIP/2.0/", "")
            ?.substringBefore(" ")
            ?.trim()
            ?.lowercase()
            ?.takeIf { it == "udp" || it == "tcp" }
            ?: fallbackTransport
        val dialogContact = SipContactHeaders.mmtelContact(
            userPart = owner,
            localEndpoint = localEndpoint,
            transport = incomingDialogTransport,
            sipInstance = SipContactHeaders.sipInstanceFromImei(imei),
        )
        Rlog.d(
            logTag,
            "Incoming dialog Contact: $dialogContact " +
                "transport=$incomingDialogTransport callId=$incomingCallId",
        )
        return dialogContact
    }
}
