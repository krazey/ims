package me.phh.sip

import android.telephony.Rlog
import java.io.OutputStream
import java.net.InetAddress
import java.net.Inet6Address

internal data class InDialogInviteSdpOffer(
    val sdp: List<String>,
    val rtpRemoteAddr: InetAddress,
    val rtpRemotePort: Int,
    val attributes: List<String>,
)

internal data class InDialogInviteMediaSelection(
    val selectedAudioCodec: NegotiatedAudioCodec,
    val amrTrack: Int,
    val amrTrackDesc: String,
    val amrFmtpAnswer: String,
    val dtmfTrack: Int,
    val dtmfTrackDesc: String,
)

internal data class InDialogInviteCallUpdateState(
    val answerSdp: ByteArray,
    val amrTrack: Int,
    val amrTrackDesc: String,
    val dtmfTrack: Int,
    val dtmfTrackDesc: String,
    val rtpRemoteAddr: InetAddress,
    val rtpRemotePort: Int,
    val remoteContact: String,
)

internal object SipInDialogInvite {

    fun parseSdpOffer(
        request: SipRequest,
        callId: String,
        cseq: String,
        logTag: String,
    ): InDialogInviteSdpOffer? {
        val sdp = request.body.toString(Charsets.UTF_8).split("[\r\n]+".toRegex()).toList()
        Rlog.d(logTag, "Handling in-dialog INVITE: callId=$callId cseq=$cseq sdp=$sdp")

        fun sdpElement(command: String): String? {
            val v = sdp.firstOrNull { it.startsWith("$command=") } ?: return null
            return v.substring(2)
        }

        val sdpConnectionData = sdpElement("c") ?: return null
        val sdpMedia = sdpElement("m") ?: return null
        val rtpRemote = sdpConnectionData.split(" ").getOrNull(2) ?: return null
        val rtpRemoteAddr = InetAddress.getByName(rtpRemote)
        val rtpRemotePort = sdpMedia.split(" ").getOrNull(1)?.toIntOrNull() ?: return null
        val attributes = sdp.filter { it.startsWith("a=") }.map { it.substring(2) }
        SipAudioCodecSdpLogger.logRemoteAudioCodecCandidates(
            tag = logTag,
            context = "remote SDP ${request.method} callId=${request.callIdOrEmpty()}",
            sdp = sdp,
        )

        return InDialogInviteSdpOffer(
            sdp = sdp,
            rtpRemoteAddr = rtpRemoteAddr,
            rtpRemotePort = rtpRemotePort,
            attributes = attributes,
        )
    }





    fun selectMedia(
        attributes: List<String>,
        selectedAudioCodec: NegotiatedAudioCodec,
        preferredAmrTrack: Int? = null,
        preferredDtmfTrack: Int? = null,
        logTag: String,
    ): InDialogInviteMediaSelection? {
        val (amrTrack, amrTrackDesc) = trackMatching(
            attributes = attributes,
            codec = SipAudioCodecNegotiator.speechCodecRtpmapName(selectedAudioCodec),
            preferredTrack = preferredAmrTrack,
            notAdditional = "octet-align=1",
            logTag = logTag,
        ) ?: return null
        val (dtmfTrack, dtmfTrackDesc) = trackMatching(
            attributes = attributes,
            codec = SipAudioCodecNegotiator.telephoneEventRtpmapName(selectedAudioCodec),
            preferredTrack = preferredDtmfTrack,
            logTag = logTag,
        ) ?: return null
        val amrFmtpAnswer =
            trackRequirements(attributes, amrTrack)
                ?: SipAudioCodecNegotiator.defaultSpeechFmtpAnswer(amrTrack, selectedAudioCodec)

        return InDialogInviteMediaSelection(
            selectedAudioCodec = selectedAudioCodec,
            amrTrack = amrTrack,
            amrTrackDesc = amrTrackDesc,
            amrFmtpAnswer = amrFmtpAnswer,
            dtmfTrack = dtmfTrack,
            dtmfTrackDesc = dtmfTrackDesc,
        )
    }

    fun trackMatching(
        attributes: List<String>,
        codec: String,
        preferredTrack: Int? = null,
        notAdditional: String = "",
        logTag: String,
    ): Pair<Int, String>? {
        val maps = attributes.filter { it.startsWith("rtpmap") && it.contains(codec) }
        val matches = maps.map { m ->
            val track = m.split("[: ]+".toRegex())[1].toInt()
            Pair(track, m)
        }

        if (preferredTrack != null) {
            val preferredMatch = matches.firstOrNull { it.first == preferredTrack }
            if (preferredMatch != null) {
                Rlog.d(
                    logTag,
                    "In-dialog INVITE reusing negotiated $codec payload " +
                        "track=$preferredTrack match=$preferredMatch",
                )
                return preferredMatch
            }

            val remappedMatch = matches.firstOrNull()
            if (remappedMatch != null) {
                Rlog.w(
                    logTag,
                    "In-dialog INVITE remapped $codec payload " +
                        "from negotiated track=$preferredTrack to track=${remappedMatch.first} " +
                        "match=$remappedMatch",
                )
                return remappedMatch
            }

            Rlog.w(
                logTag,
                "In-dialog INVITE did not offer negotiated $codec payload " +
                    "track=$preferredTrack matches=$matches; rejecting renegotiation",
            )
            return null
        }

        val sorted = if (matches.size > 1) {
            matches.sortedBy { m ->
                val fmtp = attributes.firstOrNull { it.startsWith("fmtp:${m.first}") }.orEmpty()
                when {
                    codec.startsWith("AMR") && fmtp.isEmpty() -> 100
                    notAdditional.isNotEmpty() && fmtp.contains(notAdditional) -> 90
                    else -> 10
                }
            }
        } else {
            matches
        }
        Rlog.d(logTag, "In-dialog INVITE matching $codec, got $sorted")
        return sorted.firstOrNull()
    }

    fun trackRequirements(
        attributes: List<String>,
        track: Int,
    ): String? =
        attributes.firstOrNull { it.startsWith("fmtp:$track") }


    fun callUpdateState(
        request: SipRequest,
        answerSdp: ByteArray,
        amrTrack: Int,
        amrTrackDesc: String,
        dtmfTrack: Int,
        dtmfTrackDesc: String,
        rtpRemoteAddr: InetAddress,
        rtpRemotePort: Int,
        fallbackRemoteContact: String,
        extractDestinationFromContact: (String) -> String,
    ): InDialogInviteCallUpdateState =
        InDialogInviteCallUpdateState(
            answerSdp = answerSdp,
            amrTrack = amrTrack,
            amrTrackDesc = amrTrackDesc,
            dtmfTrack = dtmfTrack,
            dtmfTrackDesc = dtmfTrackDesc,
            rtpRemoteAddr = rtpRemoteAddr,
            rtpRemotePort = rtpRemotePort,
            remoteContact = remoteContactFromRequest(
                request = request,
                fallbackRemoteContact = fallbackRemoteContact,
                extractDestinationFromContact = extractDestinationFromContact,
            ),
        )

    fun remoteContactFromRequest(
        request: SipRequest,
        fallbackRemoteContact: String,
        extractDestinationFromContact: (String) -> String,
    ): String =
        request.headers["contact"]?.getOrNull(0)
            ?.let { extractDestinationFromContact(it) }
            ?: fallbackRemoteContact

    private fun bandwidthType(line: String): String =
        line.substringBefore(':').trim()

    private fun isSupportedBandwidthLine(line: String): Boolean {
        val type = bandwidthType(line)
        return type.equals("AS", ignoreCase = true) ||
            type.equals("RS", ignoreCase = true) ||
            type.equals("RR", ignoreCase = true)
    }

    private fun deduplicateBandwidthLines(lines: List<String>): List<String> {
        val seenTypes = mutableSetOf<String>()
        val result = mutableListOf<String>()

        for (line in lines) {
            val trimmedLine = line.trim()
            val type = bandwidthType(trimmedLine)
            if (type.isEmpty()) continue

            if (seenTypes.add(type.uppercase())) {
                result += trimmedLine
            }
        }

        return result
    }

    fun buildAnswerSdp(
        attributes: List<String>,
        sdp: List<String>,
        selectedAudioCodec: NegotiatedAudioCodec,
        amrTrack: Int,
        amrTrackDesc: String,
        amrFmtpAnswer: String,
        dtmfTrack: Int,
        dtmfTrackDesc: String,
        localSdpSessionVersion: Int,
        callId: String,
        localAddr: InetAddress,
        localRtpPort: Int,
        logTag: String,
    ): ByteArray {
        val remotePtime = attributes.firstOrNull { it.startsWith("ptime:") } ?: "ptime:20"
        val remoteMaxptime = attributes.firstOrNull { it.startsWith("maxptime:") } ?: "maxptime:20"
        val allTracks = listOf(amrTrack, dtmfTrack)
        val sdpBandwidthAs = SipAudioCodecNegotiator.sdpBandwidthAsKbps(selectedAudioCodec)

        fun bandwidthLines(lines: List<String>): List<String> {
            val seenTypes = mutableSetOf<String>()
            return lines
                .filter { it.startsWith("b=", ignoreCase = true) }
                .map { it.substring(2).trim() }
                .filter { it.isNotEmpty() }
                .filter { bandwidthLine ->
                    val type = bandwidthLine.substringBefore(':').trim().uppercase()
                    type.isNotEmpty() && seenTypes.add(type)
                }
        }

        val firstAudioMediaIndex = sdp.indexOfFirst {
            it.startsWith("m=audio", ignoreCase = true)
        }
        val nextMediaIndex = if (firstAudioMediaIndex >= 0) {
            val nextRelativeIndex = sdp
                .drop(firstAudioMediaIndex + 1)
                .indexOfFirst { it.startsWith("m=", ignoreCase = true) }

            if (nextRelativeIndex >= 0) {
                firstAudioMediaIndex + 1 + nextRelativeIndex
            } else {
                sdp.size
            }
        } else {
            sdp.size
        }

        val sessionBandwidthLines = bandwidthLines(
            sdp.take(if (firstAudioMediaIndex >= 0) firstAudioMediaIndex else sdp.size)
        )
        val mediaBandwidthLines = if (firstAudioMediaIndex >= 0) {
            bandwidthLines(sdp.subList(firstAudioMediaIndex + 1, nextMediaIndex))
        } else {
            emptyList()
        }
        val answerBandwidthLines = when {
            mediaBandwidthLines.isNotEmpty() -> mediaBandwidthLines
            sessionBandwidthLines.isNotEmpty() -> sessionBandwidthLines
            else -> listOf("AS:$sdpBandwidthAs")
        }
        val remoteDirection = attributes.firstOrNull {
            it == "sendrecv" || it == "sendonly" || it == "recvonly" || it == "inactive"
        }
        val answerDirection = when (remoteDirection) {
            "sendonly" -> "recvonly"
            "recvonly" -> "sendonly"
            "inactive" -> "inactive"
            "sendrecv" -> "sendrecv"
            else -> null
        }
        Rlog.d(
            logTag,
            "Conservative in-dialog INVITE SDP answer: " +
                "bandwidth=$answerBandwidthLines ptime=$remotePtime maxptime=$remoteMaxptime " +
                "remoteDirection=$remoteDirection answerDirection=$answerDirection"
        )
        Rlog.d(
            logTag,
            "In-dialog INVITE SDP answer version: callId=$callId version=$localSdpSessionVersion",
        )
        val ipType = if (localAddr is Inet6Address) "IP6" else "IP4"
        val answerSdpLines = mutableListOf(
            "v=0",
            "o=- 1 $localSdpSessionVersion IN $ipType ${localAddr.hostAddress}",
            "s=-",
            "c=IN $ipType ${localAddr.hostAddress}",
            "t=0 0",
            "m=audio $localRtpPort RTP/AVP ${allTracks.joinToString(" ")}",
        )
        answerBandwidthLines.forEach { answerSdpLines += "b=$it" }
        answerSdpLines += listOf(
            "a=$amrTrackDesc",
            "a=$remotePtime",
            "a=$remoteMaxptime",
            "a=$dtmfTrackDesc",
            "a=$amrFmtpAnswer",
            "a=fmtp:$dtmfTrack 0-15",
        )
        answerDirection?.let { answerSdpLines += "a=$it" }
        return answerSdpLines.joinToString("\r\n").toByteArray(Charsets.US_ASCII)
    }

    fun sessionTimerHeaders(
        request: SipRequest,
        logTag: String,
    ): Map<String, List<String>> {
        val requestSessionExpires = request.headers["session-expires"]?.getOrNull(0)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val requestMinSe = request.headers["min-se"]?.getOrNull(0)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val inDialogSessionTimerHeaders = mutableMapOf<String, List<String>>()
        requestSessionExpires?.let { inDialogSessionTimerHeaders["session-expires"] = listOf(it) }
        requestMinSe?.let { inDialogSessionTimerHeaders["min-se"] = listOf(it) }
        Rlog.d(
            logTag,
            "In-dialog INVITE session timer response headers: " +
                "Session-Expires=$requestSessionExpires Min-SE=$requestMinSe",
        )
        return inDialogSessionTimerHeaders
    }


    fun writeOkResponse(
        responseWriter: OutputStream,
        response: SipResponse,
    ): Boolean {
        return SipMessageWriter.write(
            responseWriter,
            response.toByteArray(),
            "in-dialog INVITE 200 response",
        )
    }

    fun okResponseWithSdp(
        request: SipRequest,
        contact: String,
        answerSdp: ByteArray,
        inDialogSessionTimerHeaders: Map<String, List<String>>,
        logTag: String,
    ): SipResponse {
        val responseHeaders = SipDialogHeaderBuilder.responseHeadersFromRequest(
            request,
            extra = """
                Contact: $contact
                Supported: timer
                Content-Type: application/sdp
            """.toSipHeadersMap() + inDialogSessionTimerHeaders
        )
        val response = SipResponse(
            statusCode = 200,
            statusString = "OK",
            headersParam = responseHeaders,
            body = answerSdp,
        )
        Rlog.d(logTag, "Replying to in-dialog INVITE without creating a new incoming call: $response")
        return response
    }


}
