package me.phh.sip

import android.telephony.Rlog
import java.net.InetAddress

internal data class IncomingInviteSdpBasics(
    val sdp: List<String>,
    val attributes: List<String>,
    val rtpRemoteAddr: InetAddress,
    val rtpRemotePort: String,
)

internal object SipIncomingInviteSdpParser {
    fun parseBasics(
        request: SipRequest,
        logTag: String,
    ): IncomingInviteSdpBasics {
        val sdp = request.body.toString(Charsets.UTF_8).split("[\r\n]+".toRegex()).toList()
        Rlog.d(logTag, "Parsed incoming SDP lines=${sdp.size} bytes=${request.body.size}")
        fun sdpElement(command: String): String? {
            val v = sdp.firstOrNull { it.startsWith("$command=")} ?: return null
            return v.substring(2)
        }
        val sdpConnectionData = sdpElement("c")
        val sdpOrigin = sdpElement("o")
        val sdpSessionName = sdpElement("s")
        val sdpTiming = sdpElement("t")
        val sdpBandwidth = sdpElement("b")
        val sdpMedia = sdpElement("m")

        Rlog.d(logTag, "Got sdpTiming $sdpTiming")

        if (sdpTiming != "0 0")
            Rlog.d(logTag, "Uh-oh, unknown timing mode")


        val rtpRemote = sdpConnectionData!!.split(" ")[2] //c=IN IP6 xxx
        val rtpRemoteAddr = InetAddress.getByName(rtpRemote)
        val rtpRemotePort = sdpMedia!!.split(" ")[1] //m=audio 30798 RTP/AVP 96 97 98 8 18 101 100 99

        val attributes = sdp.filter { it.startsWith("a=") }.map { it.substring(2)}
        SipAudioCodecSdpLogger.logRemoteAudioCodecCandidates(
            tag = logTag,
            context = "remote SDP ${request.method} callId=${request.callIdOrEmpty()}",
            sdp = sdp,
        )

        return IncomingInviteSdpBasics(
            sdp = sdp,
            attributes = attributes,
            rtpRemoteAddr = rtpRemoteAddr,
            rtpRemotePort = rtpRemotePort,
        )
    }
}

internal data class IncomingInviteCapabilities(
    val peerSupportsEarlyMedia: Boolean,
    val callerSupportsPrecondition: Boolean,
    val sendReliable183: Boolean,
    val remoteMaxptime: String,
)

internal object SipIncomingInviteCapabilityParser {
    fun parse(
        request: SipRequest,
        attributes: List<String>,
        logTag: String,
    ): IncomingInviteCapabilities {
        val peerSupportsEarlyMedia = request.headers["p-early-media"]?.isNotEmpty() == true
        val callerCapabilityHeaders =
            request.headers["supported"].orEmpty() + request.headers["require"].orEmpty()
        val callerSupports100Rel = callerCapabilityHeaders
            .any { it.contains("100rel", ignoreCase = true) }
        val callerSupportsPreconditionHeader = callerCapabilityHeaders
            .any { it.contains("precondition", ignoreCase = true) }
        val incomingOfferHasPrecondition = attributes.any { attr ->
            attr.startsWith("curr:qos", ignoreCase = true) ||
                attr.startsWith("des:qos", ignoreCase = true) ||
                attr.startsWith("conf:qos", ignoreCase = true)
        }
        val incomingOfferIsInactive = attributes.any { it.equals("inactive", ignoreCase = true) }
        val callerSupportsPrecondition = callerSupportsPreconditionHeader || incomingOfferHasPrecondition
        // Some carriers send incoming VoLTE as inactive media with mandatory QoS
        // preconditions and will not open downlink RTP until the provisional SDP is
        // acknowledged with PRACK. Keep the old plain-180 path for simple incoming
        // offers because at least one tested network did not PRACK reliable 183.
        val sendReliable183 =
            callerSupports100Rel &&
                callerSupportsPrecondition &&
                incomingOfferHasPrecondition &&
                incomingOfferIsInactive
        val remoteMaxptime = attributes.firstOrNull { it.startsWith("maxptime:") } ?: "maxptime:20"
        Rlog.d(
            logTag,
            "Incoming early-media support=$peerSupportsEarlyMedia " +
                "sendReliable183=$sendReliable183 " +
                "supports100rel=$callerSupports100Rel " +
                "callerSupportsPrecondition=$callerSupportsPrecondition " +
                "headerPrecondition=$callerSupportsPreconditionHeader " +
                "sdpPrecondition=$incomingOfferHasPrecondition " +
                "inactiveOffer=$incomingOfferIsInactive " +
                "remoteMaxptime=$remoteMaxptime",
        )

        return IncomingInviteCapabilities(
            peerSupportsEarlyMedia = peerSupportsEarlyMedia,
            callerSupportsPrecondition = callerSupportsPrecondition,
            sendReliable183 = sendReliable183,
            remoteMaxptime = remoteMaxptime,
        )
    }
}

internal data class IncomingInviteMediaSelection(
    val selectedAudioCodec: NegotiatedAudioCodec,
    val amrTrack: Int,
    val amrTrackDesc: String,
    val amrFmtpAnswer: String,
    val dtmfTrack: Int,
    val dtmfTrackDesc: String,
    val allTracks: List<Int>,
    val sdpBandwidthAs: Int,
)

internal object SipIncomingInviteMediaSelector {
    fun select(
        sdp: List<String>,
        attributes: List<String>,
        incomingCallId: String,
        amrWbMediaCodecAvailable: Boolean,
        logTag: String,
    ): IncomingInviteMediaSelection? {
        fun lookTrackMatching(codec: String, additional: String = "", notAdditional: String = ""): Pair<Int,String>? {
            //TODO: also match on fmtp
            val maps = attributes.filter { it.startsWith("rtpmap") && it.contains(codec) }
            val matches = maps.map { m ->
                val track = m.split("[: ]+".toRegex())[1].toInt()
                val desc = m
                Pair(track, desc)
            }
            Rlog.d(logTag, "Matching $codec, got $matches")
            val matches2 = if(matches.size > 1) {
                matches.sortedBy { m ->
                    val fmtp = attributes.firstOrNull { it.startsWith("fmtp:${m.first}") }.orEmpty()
                    Rlog.d(logTag, "Matching $codec, for match $m got fmtp $fmtp")
                    when {
                        // For AMR, do not prefer an rtpmap-only payload when valid fmtp payloads exist.
                        codec.startsWith("AMR") && fmtp.isEmpty() -> 100

                        // This stack currently sends bandwidth-efficient AMR, so avoid octet-align=1.
                        notAdditional.isNotEmpty() && fmtp.contains(notAdditional) -> 90

                        // Optional positive preference for codecs/callers where we have one.
                        additional.isNotEmpty() && fmtp.contains(additional) -> 0

                        else -> 10
                    }
                }
            } else {
                matches
            }
            Rlog.d(logTag, "Matching2 $codec, got $matches2")
            return matches2.firstOrNull()
        }

        fun trackRequirements(track: Int): String? {
            return attributes.firstOrNull() { it.startsWith("fmtp:$track") }
        }

        val selectedAudioCodec = SipAudioCodecNegotiator.selectIncomingSpeechCodecFromOffer(
            logTag = logTag,
            sdp = sdp,
            context = "incoming INVITE callId=$incomingCallId",
            amrWbMediaCodecAvailable = amrWbMediaCodecAvailable,
        )

        val preferredAmrNbTrack = if (selectedAudioCodec == SipAudioCodecs.AMR_NB) {
            lookTrackMatching(
                SipAudioCodecNegotiator.speechCodecRtpmapName(selectedAudioCodec),
                additional = "mode-set=7",
                notAdditional = "octet-align=1",
            )
        } else {
            null
        }
        val (amrTrack, amrTrackDesc) = preferredAmrNbTrack ?: lookTrackMatching(
            SipAudioCodecNegotiator.speechCodecRtpmapName(selectedAudioCodec),
            additional = "",
            notAdditional = "octet-align=1",
        ) ?: return null
        val amrTrackRequirements = trackRequirements(amrTrack)
        Rlog.d(
            logTag,
            "Selected incoming speech payload track=$amrTrack codec=${selectedAudioCodec.name} " +
                "desc=$amrTrackDesc fmtp=${amrTrackRequirements.orEmpty()} callId=$incomingCallId",
        )
        val amrFmtpAnswer = amrTrackRequirements ?: SipAudioCodecNegotiator.defaultSpeechFmtpAnswer(amrTrack, selectedAudioCodec)

        val (dtmfTrack, dtmfTrackDesc) =
            lookTrackMatching(SipAudioCodecNegotiator.telephoneEventRtpmapName(selectedAudioCodec)) ?: return null

        val allTracks = listOf(amrTrack, dtmfTrack)
        val sdpBandwidthAs = SipAudioCodecNegotiator.sdpBandwidthAsKbps(selectedAudioCodec)

        return IncomingInviteMediaSelection(
            selectedAudioCodec = selectedAudioCodec,
            amrTrack = amrTrack,
            amrTrackDesc = amrTrackDesc,
            amrFmtpAnswer = amrFmtpAnswer,
            dtmfTrack = dtmfTrack,
            dtmfTrackDesc = dtmfTrackDesc,
            allTracks = allTracks,
            sdpBandwidthAs = sdpBandwidthAs,
        )
    }
}

internal data class IncomingInviteOffer(
    val callerNumber: String,
    val callerPresentationRestricted: Boolean,
    val sdp: List<String>,
    val rtpRemoteAddr: InetAddress,
    val rtpRemotePort: String,
    val attributes: List<String>,
    val peerSupportsEarlyMedia: Boolean,
    val callerSupportsPrecondition: Boolean,
    val sendReliable183: Boolean,
    val remoteMaxptime: String,
    val selectedAudioCodec: NegotiatedAudioCodec,
    val amrTrack: Int,
    val amrTrackDesc: String,
    val amrFmtpAnswer: String,
    val dtmfTrack: Int,
    val dtmfTrackDesc: String,
    val allTracks: List<Int>,
    val sdpBandwidthAs: Int,
    val owner: String,
)

internal object SipIncomingInviteOfferBuilder {
    fun build(
        request: SipRequest,
        callerNumber: String,
        callerPresentationRestricted: Boolean,
        sdpBasics: IncomingInviteSdpBasics,
        capabilities: IncomingInviteCapabilities,
        mediaSelection: IncomingInviteMediaSelection,
    ): IncomingInviteOffer {
        // destination is sip:<owner>@realm, extract owner
        val owner = request.destination.substringAfter("sip:").substringBefore("@")

        return IncomingInviteOffer(
            callerNumber = callerNumber,
            callerPresentationRestricted = callerPresentationRestricted,
            sdp = sdpBasics.sdp,
            rtpRemoteAddr = sdpBasics.rtpRemoteAddr,
            rtpRemotePort = sdpBasics.rtpRemotePort,
            attributes = sdpBasics.attributes,
            peerSupportsEarlyMedia = capabilities.peerSupportsEarlyMedia,
            callerSupportsPrecondition = capabilities.callerSupportsPrecondition,
            sendReliable183 = capabilities.sendReliable183,
            remoteMaxptime = capabilities.remoteMaxptime,
            selectedAudioCodec = mediaSelection.selectedAudioCodec,
            amrTrack = mediaSelection.amrTrack,
            amrTrackDesc = mediaSelection.amrTrackDesc,
            amrFmtpAnswer = mediaSelection.amrFmtpAnswer,
            dtmfTrack = mediaSelection.dtmfTrack,
            dtmfTrackDesc = mediaSelection.dtmfTrackDesc,
            allTracks = mediaSelection.allTracks,
            sdpBandwidthAs = mediaSelection.sdpBandwidthAs,
            owner = owner,
        )
    }
}


internal object SipIncomingInviteOfferParser {
    fun parse(
        request: SipRequest,
        incomingCallId: String,
        logTag: String,
        hasIncomingResponseWriter: Boolean,
        amrWbMediaCodecAvailable: Boolean,
        extractCallerNumberFromHeader: (String) -> String,
    ): IncomingInviteOffer? {
        val incomingIdentity = SipIncomingIdentityResolver.resolve(request.headers)
        val callerNumber = incomingIdentity.identityHeader
            ?.let(extractCallerNumberFromHeader)
            .orEmpty()
        Rlog.d(
            logTag,
            "Incoming identity resolved restricted=${incomingIdentity.presentationRestricted} " +
                "callId=$incomingCallId hasIncomingResponseWriter=$hasIncomingResponseWriter",
        )

        // We'll have three states:
        // - 100 Trying (this will be done by returning 100 in this function)
        // - 183 Session Progress network-wise we're ready to receive data
        // - 180 Ringing Notification's AudioTrack is playing, the user can hear its phone -- Note: Ringing doesn't give SDP
        // - 200 User has accepted the call

        val incomingSdpBasics = SipIncomingInviteSdpParser.parseBasics(request = request, logTag = logTag)

        val incomingCapabilities = SipIncomingInviteCapabilityParser.parse(
            request = request,
            attributes = incomingSdpBasics.attributes,
            logTag = logTag,
        )

        val incomingMediaSelection = SipIncomingInviteMediaSelector.select(
            sdp = incomingSdpBasics.sdp,
            attributes = incomingSdpBasics.attributes,
            incomingCallId = incomingCallId,
            amrWbMediaCodecAvailable = amrWbMediaCodecAvailable,
            logTag = logTag,
        ) ?: return null

        return SipIncomingInviteOfferBuilder.build(
            request = request,
            callerNumber = callerNumber,
            callerPresentationRestricted = incomingIdentity.presentationRestricted,
            sdpBasics = incomingSdpBasics,
            capabilities = incomingCapabilities,
            mediaSelection = incomingMediaSelection,
        )
    }
}
