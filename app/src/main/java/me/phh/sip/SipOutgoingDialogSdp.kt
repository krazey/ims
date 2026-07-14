package me.phh.sip

import android.telephony.Rlog
import java.net.DatagramSocket
import java.net.InetAddress


internal data class OutgoingDialogSdpAnswer(
    val isPrecondition: Boolean,
    val respSdp: List<String>,
    val dialogAudioCodec: NegotiatedAudioCodec,
    val dialogAmrTrack: Int,
    val dialogAmrTrackDesc: String,
    val dialogDtmfTrack: Int,
    val dialogDtmfTrackDesc: String,
    val rtpRemoteAddr: InetAddress,
    val rtpRemotePortInt: Int,
)

internal data class OutgoingDialogMediaSelection(
    val dialogAudioCodec: NegotiatedAudioCodec,
    val dialogAmrTrack: Int,
    val dialogAmrTrackDesc: String,
    val dialogDtmfTrack: Int,
    val dialogDtmfTrackDesc: String,
)
internal data class OutgoingDialogRtpEndpoint(
    val rtpRemoteAddr: InetAddress,
    val rtpRemotePortInt: Int,
)

internal data class OutgoingDialogSdpInstallResult(
    val responseCseq: String,
    val outgoingMediaFormatChanged: Boolean,
)

internal data class OutgoingPrecondition183State(
    val remoteHasLocalQos: Boolean,
    val needsLocalQosUpdate: Boolean,
)

internal data class OutgoingFinalInviteSdpMediaState(
    val finalInviteCallId: String,
    val finalInviteAfterLocalCancel: Boolean,
)

internal enum class OutgoingFinalInviteMediaThreadAction {
    START,
    RESTART,
    ALREADY_STARTED,
}

internal object SipOutgoingDialogSdp {













    fun finalAnswerAfterLocalCancelReason(): String =
        "final answer after local CANCEL"

    fun finalInviteAnswerReason(): String =
        "final INVITE answer"

    fun finalInviteMediaThreadAction(
        startedNow: Boolean,
        outgoingMediaFormatChanged: Boolean,
    ): OutgoingFinalInviteMediaThreadAction =
        when {
            startedNow -> OutgoingFinalInviteMediaThreadAction.START
            outgoingMediaFormatChanged -> OutgoingFinalInviteMediaThreadAction.RESTART
            else -> OutgoingFinalInviteMediaThreadAction.ALREADY_STARTED
        }

    fun finalInviteMediaThreadLogMessage(
        action: OutgoingFinalInviteMediaThreadAction,
        mediaRestartGeneration: Int?,
        answer: OutgoingDialogSdpAnswer,
    ): String =
        when (action) {
            OutgoingFinalInviteMediaThreadAction.START ->
                "Starting outgoing media threads from final INVITE SDP"
            OutgoingFinalInviteMediaThreadAction.RESTART ->
                "Restarting outgoing media threads after final INVITE SDP media change: " +
                    "generation=$mediaRestartGeneration " +
                    "codec=${answer.dialogAudioCodec.name}/${answer.dialogAudioCodec.sampleRate} " +
                    "amrTrack=${answer.dialogAmrTrack} dtmfTrack=${answer.dialogDtmfTrack}"
            OutgoingFinalInviteMediaThreadAction.ALREADY_STARTED ->
                "Outgoing media threads already started before final INVITE SDP"
        }

    fun finalInviteSdpMediaState(
        logTag: String,
        response: SipResponse,
        responseCseq: String,
        finalInviteAfterLocalCancel: Boolean,
    ): OutgoingFinalInviteSdpMediaState? {
        if (!responseCseq.contains("INVITE") || (response.statusCode != 200 && response.statusCode != 202)) {
            return null
        }

        val finalInviteCallId = response.callIdOrEmpty()
        if (finalInviteAfterLocalCancel) {
            Rlog.w(logTag, "Confirmed outgoing dialog after local CANCEL; sending BYE immediately callId=$finalInviteCallId")
        }

        return OutgoingFinalInviteSdpMediaState(
            finalInviteCallId = finalInviteCallId,
            finalInviteAfterLocalCancel = finalInviteAfterLocalCancel,
        )
    }

    fun nextLocalCseqForDialog(
        response: SipResponse,
        outgoingDialogNextCseq: Int,
        currentCallLocalCseq: Int,
    ): Int {
        val inviteCseqForDialog = response.headers["cseq"]!![0].substringBefore(" ").toIntOrNull() ?: 1
        return maxOf(
            inviteCseqForDialog + 1,
            outgoingDialogNextCseq,
            currentCallLocalCseq,
        )
    }


    fun startingOutgoingMediaFromPrecondition183SdpLog(): String =
        "Starting outgoing media threads from precondition 183 SDP"

    fun sendingPreconditionUpdateLog(request: SipRequest): String =
        "Sending ${request.safeLogSummary()}"

    fun preconditionUpdateWriteLabel(): String =
        "SipHandler msg2"

    fun buildPreconditionUpdateRequest(
        remoteContact: String?,
        fallbackTarget: String,
        updateHeaders: Map<String, List<String>>,
        newSdp: ByteArray,
    ): SipRequest =
        SipRequest(
            SipMethod.UPDATE,
            remoteContact ?: fallbackTarget,
            updateHeaders,
            newSdp,
        )

    fun precondition183State(
        logTag: String,
        respSdp: List<String>,
    ): OutgoingPrecondition183State {
        Rlog.d(logTag, "Handling precondition...")
        val currLocal = respSdp.first { it.startsWith("a=curr:qos local")}
        // No resource has been allocated at either side
        val localNone = currLocal.contains("none")
        Rlog.d(logTag, "precondition: Curr is $currLocal $localNone")
        val currRemote = respSdp.first { it.startsWith("a=curr:qos remote")}
        val remoteNone = currRemote.contains("none")
        val remoteHasLocalQos = currLocal.contains("sendrecv")
        val needsLocalQosUpdate = localNone || remoteNone
        Rlog.d(logTag, "precondition: Remote is $currRemote remoteNone=$remoteNone remoteHasLocalQos=$remoteHasLocalQos needsLocalQosUpdate=$needsLocalQosUpdate")

        return OutgoingPrecondition183State(
            remoteHasLocalQos = remoteHasLocalQos,
            needsLocalQosUpdate = needsLocalQosUpdate,
        )
    }

    fun buildPreconditionUpdateSdp(
        originalInviteSdp: ByteArray,
        respSdp: List<String>,
        remoteHasLocalQos: Boolean,
        nextLocalSdpVersion: () -> Int,
    ): ByteArray {
        val remoteMaxptimeLine = respSdp.firstOrNull { it.startsWith("a=maxptime:") } ?: "a=maxptime:40"

        val localUpdateSdpLines = originalInviteSdp.toString(Charsets.UTF_8)
            .split("[\r\n]+".toRegex())
            .filter { it.isNotBlank() }
            .map { line ->
                when {
                    line.startsWith("o=") -> {
                        val v = nextLocalSdpVersion()
                        line.replace(Regex("^(o=\\S+\\s+\\S+\\s+)\\S+(\\s+IN\\s+IP[46]\\s+.*)$"), "$1$v$2")
                    }
                    line.startsWith("a=maxptime:") -> remoteMaxptimeLine
                    line.startsWith("a=curr:qos local") -> "a=curr:qos local sendrecv"
                    line.startsWith("a=curr:qos remote") -> if (remoteHasLocalQos) "a=curr:qos remote sendrecv" else "a=curr:qos remote none"
                    else -> line
                }
            }
            .let { lines ->
                if (lines.any { it.startsWith("a=conf:qos remote") }) {
                    lines
                } else {
                    lines + "a=conf:qos remote sendrecv"
                }
            }

        return localUpdateSdpLines.joinToString("\r\n").toByteArray(Charsets.US_ASCII)
    }


    fun startingOutgoingMediaFromNonPrecondition183SdpLog(): String =
        "Starting outgoing media threads from non-precondition 183 SDP"


    fun shouldStartMediaForNonPrecondition183(
        response: SipResponse,
        isPrecondition: Boolean,
    ): Boolean =
        !isPrecondition && response.statusCode == 183

    fun installLogMessage(
        response: SipResponse,
        responseCseq: String,
        audioCodec: NegotiatedAudioCodec?,
        amrTrack: Int?,
        dtmfTrack: Int?,
        remoteTarget: String?,
        nextLocalCseq: Int?,
        route: List<String>?,
    ): String {
        val outgoingDialogPhase = installPhase(
            response = response,
            responseCseq = responseCseq,
        )
        return "Outgoing $outgoingDialogPhase dialog SDP: status=${response.statusCode} cseq=$responseCseq " +
            "codec=${audioCodec?.name}/${audioCodec?.sampleRate} " +
            "amrTrack=$amrTrack dtmfTrack=$dtmfTrack " +
            "remoteTarget=$remoteTarget nextLocalCseq=$nextLocalCseq " +
            "route=$route"
    }

    fun finalInviteMediaFormatChanged(
        logTag: String,
        threadsStarted: Boolean,
        response: SipResponse,
        responseCseq: String,
        previousDialogPresent: Boolean,
        previousAudioCodec: NegotiatedAudioCodec?,
        previousAmrTrack: Int?,
        previousDtmfTrack: Int?,
        previousRtpRemoteAddr: InetAddress?,
        previousRtpRemotePort: Int?,
        answer: OutgoingDialogSdpAnswer,
    ): Boolean {
        val outgoingMediaFormatChanged =
            threadsStarted &&
                previousDialogPresent &&
                responseCseq.contains("INVITE") &&
                (response.statusCode == 200 || response.statusCode == 202) &&
                (
                    previousAudioCodec != answer.dialogAudioCodec ||
                        previousAmrTrack != answer.dialogAmrTrack ||
                        previousDtmfTrack != answer.dialogDtmfTrack ||
                        previousRtpRemoteAddr != answer.rtpRemoteAddr ||
                        previousRtpRemotePort != answer.rtpRemotePortInt
                )
        if (outgoingMediaFormatChanged) {
            Rlog.w(
                logTag,
                "Outgoing final INVITE SDP changed running media format: " +
                    "oldCodec=${previousAudioCodec?.name}/${previousAudioCodec?.sampleRate} " +
                    "oldAmr=$previousAmrTrack oldDtmf=$previousDtmfTrack " +
                    "oldRtp=$previousRtpRemoteAddr:$previousRtpRemotePort " +
                    "newCodec=${answer.dialogAudioCodec.name}/${answer.dialogAudioCodec.sampleRate} " +
                    "newAmr=${answer.dialogAmrTrack} newDtmf=${answer.dialogDtmfTrack} " +
                    "newRtp=${answer.rtpRemoteAddr}:${answer.rtpRemotePortInt}",
            )
        }
        return outgoingMediaFormatChanged
    }

    fun installPhase(
        response: SipResponse,
        responseCseq: String,
    ): String =
        when {
            responseCseq.contains("UPDATE") -> "update"
            responseCseq.contains("INVITE") && (response.statusCode == 200 || response.statusCode == 202) -> "final-answer"
            response.statusCode in 180..199 -> "early"
            else -> "sdp"
        }

    fun parseAnswer(
        logTag: String,
        response: SipResponse,
        rtpSocket: DatagramSocket,
        amrNbTrack: Int,
        dtmfNbTrack: Int,
        amrWbMediaCodecAvailable: Boolean,
    ): OutgoingDialogSdpAnswer? {
        val isSdp = response.headers["content-type"]?.get(0) == "application/sdp"
        val isPrecondition = response.headers["require"]?.find { it.contains("precondition") } != null

        if (!isSdp) return null

        val respSdp = response.body.toString(Charsets.UTF_8).split("[\r\n]+".toRegex()).toList()
        SipAudioCodecSdpLogger.logRemoteAudioCodecCandidates(
            tag = logTag,
            context = "outgoing SDP response ${response.statusCode} callId=${response.callIdOrEmpty()}",
            sdp = respSdp,
        )

        val respAttributes = respSdp
            .filter { it.startsWith("a=") }
            .map { it.substring(2) }
        val outgoingDialogMediaSelection = selectMediaFromAnswer(
            logTag = logTag,
            response = response,
            respSdp = respSdp,
            respAttributes = respAttributes,
            amrNbTrack = amrNbTrack,
            dtmfNbTrack = dtmfNbTrack,
            amrWbMediaCodecAvailable = amrWbMediaCodecAvailable,
        )
        val outgoingDialogRtpEndpoint = connectRtpEndpointFromAnswer(
            logTag = logTag,
            respSdp = respSdp,
            rtpSocket = rtpSocket,
        )

        return buildAnswer(
            isPrecondition = isPrecondition,
            respSdp = respSdp,
            mediaSelection = outgoingDialogMediaSelection,
            rtpEndpoint = outgoingDialogRtpEndpoint,
        )
    }

    fun selectMediaFromAnswer(
        logTag: String,
        response: SipResponse,
        respSdp: List<String>,
        respAttributes: List<String>,
        amrNbTrack: Int,
        dtmfNbTrack: Int,
        amrWbMediaCodecAvailable: Boolean,
    ): OutgoingDialogMediaSelection {
        fun sdpElement(command: String): String? {
            val v = respSdp.firstOrNull { it.startsWith("$command=")} ?: return null
            return v.substring(2)
        }

        fun responseTrackRequirements(track: Int): String? =
            respAttributes.firstOrNull { it.startsWith("fmtp:$track") }

        fun lookResponseTrackMatching(codec: String, notAdditional: String = ""): Pair<Int, String>? {
            val offeredPayloads = sdpElement("m")
                ?.trim()
                ?.split("\\s+".toRegex())
                ?.drop(3)
                ?.mapNotNull { it.toIntOrNull() }
                ?.toSet()
                .orEmpty()
            val maps = respAttributes.filter { it.startsWith("rtpmap:") && it.contains(codec) }
            val matches = maps.mapNotNull { m ->
                val track = m.split("[: ]+".toRegex()).getOrNull(1)?.toIntOrNull()
                if (track != null && offeredPayloads.contains(track)) Pair(track, m) else null
            }
            val sorted = matches.sortedBy { m ->
                val fmtp = responseTrackRequirements(m.first).orEmpty()
                when {
                    fmtp.contains("octet-align=1", ignoreCase = true) &&
                        notAdditional.isNotEmpty() &&
                        fmtp.contains(notAdditional, ignoreCase = true) -> 100
                    fmtp.contains("octet-align=1", ignoreCase = true) -> 100
                    else -> 0
                }
            }
            Rlog.d(logTag, "Outgoing answer matching $codec offered=$offeredPayloads got=$sorted")
            return sorted.firstOrNull()
        }

        val selectedAudioCodec = SipAudioCodecNegotiator.selectOutgoingSpeechCodecFromAnswer(
            logTag = logTag,
            sdp = respSdp,
            context = "outgoing SDP response ${response.statusCode} callId=${response.callIdOrEmpty()}",
            amrWbMediaCodecAvailable = amrWbMediaCodecAvailable,
        )
        val selectedAmr = lookResponseTrackMatching(
            SipAudioCodecNegotiator.speechCodecRtpmapName(selectedAudioCodec),
            notAdditional = "octet-align=1",
        )
        if (selectedAmr == null) {
            Rlog.w(
                logTag,
                "Outgoing SDP response lacks compatible ${SipAudioCodecNegotiator.speechCodecRtpmapName(selectedAudioCodec)}; " +
                    "falling back to AMR-NB/8000 tracks",
            )
        }
        val selectedDtmf = lookResponseTrackMatching(
            SipAudioCodecNegotiator.telephoneEventRtpmapName(selectedAudioCodec),
        )
        if (selectedDtmf == null) {
            Rlog.w(
                logTag,
                "Outgoing SDP response lacks compatible ${SipAudioCodecNegotiator.telephoneEventRtpmapName(selectedAudioCodec)}; " +
                    "falling back to telephone-event/8000",
            )
        }
        val dialogAudioCodec =
            if (selectedAmr != null && selectedDtmf != null) selectedAudioCodec else SipAudioCodecs.AMR_NB
        val (dialogAmrTrack, dialogAmrTrackDesc) =
            selectedAmr?.takeIf { selectedDtmf != null } ?: (amrNbTrack to "rtpmap:$amrNbTrack AMR/8000/1")
        val (dialogDtmfTrack, dialogDtmfTrackDesc) =
            selectedDtmf?.takeIf { selectedAmr != null } ?: (dtmfNbTrack to "rtpmap:$dtmfNbTrack telephone-event/8000")

        return OutgoingDialogMediaSelection(
            dialogAudioCodec = dialogAudioCodec,
            dialogAmrTrack = dialogAmrTrack,
            dialogAmrTrackDesc = dialogAmrTrackDesc,
            dialogDtmfTrack = dialogDtmfTrack,
            dialogDtmfTrackDesc = dialogDtmfTrackDesc,
        )
    }

    fun buildAnswer(
        isPrecondition: Boolean,
        respSdp: List<String>,
        mediaSelection: OutgoingDialogMediaSelection,
        rtpEndpoint: OutgoingDialogRtpEndpoint,
    ): OutgoingDialogSdpAnswer =
        OutgoingDialogSdpAnswer(
            isPrecondition = isPrecondition,
            respSdp = respSdp,
            dialogAudioCodec = mediaSelection.dialogAudioCodec,
            dialogAmrTrack = mediaSelection.dialogAmrTrack,
            dialogAmrTrackDesc = mediaSelection.dialogAmrTrackDesc,
            dialogDtmfTrack = mediaSelection.dialogDtmfTrack,
            dialogDtmfTrackDesc = mediaSelection.dialogDtmfTrackDesc,
            rtpRemoteAddr = rtpEndpoint.rtpRemoteAddr,
            rtpRemotePortInt = rtpEndpoint.rtpRemotePortInt,
        )

    fun connectRtpEndpointFromAnswer(
        logTag: String,
        respSdp: List<String>,
        rtpSocket: DatagramSocket,
    ): OutgoingDialogRtpEndpoint {
        fun sdpElement(command: String): String? {
            val v = respSdp.firstOrNull { it.startsWith("$command=")} ?: return null
            return v.substring(2)
        }

        val rtpRemotePort = sdpElement("m")!!.split(" ")[1]
        val rtpRemoteAddr = InetAddress.getByName(sdpElement("c")!!.split(" ")[2])
        val rtpRemotePortInt = rtpRemotePort.toInt()
        try {
            if (!rtpSocket.isConnected || rtpSocket.inetAddress != rtpRemoteAddr || rtpSocket.port != rtpRemotePortInt) {
                rtpSocket.connect(rtpRemoteAddr, rtpRemotePortInt)
                Rlog.d(logTag, "Outgoing RTP socket connected to ${rtpRemoteAddr}:${rtpRemotePortInt} local=${rtpSocket.localAddress}:${rtpSocket.localPort}")
            }
        } catch (e: Exception) {
            Rlog.w(logTag, "Failed to connect outgoing RTP socket to ${rtpRemoteAddr}:${rtpRemotePortInt}", e)
        }

        return OutgoingDialogRtpEndpoint(
            rtpRemoteAddr = rtpRemoteAddr,
            rtpRemotePortInt = rtpRemotePortInt,
        )
    }
}
