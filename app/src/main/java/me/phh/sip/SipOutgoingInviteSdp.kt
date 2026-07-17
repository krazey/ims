package me.phh.sip

import android.telephony.Rlog
import java.net.DatagramSocket

internal data class OutgoingInviteSdpOffer(
    val amrNbTrack: Int,
    val dtmfNbTrack: Int,
    val sdp: ByteArray,
    val inviteBody: ByteArray,
)

private data class OutgoingInviteSdpMediaOffer(
    val amrNbTrack: Int,
    val amrWbTrack: Int,
    val dtmfNbTrack: Int,
    val dtmfWbTrack: Int,
    val offerAmrWb: Boolean,
    val allTracks: List<Int>,
    val offerBandwidthAs: Int,
)

internal object SipOutgoingInviteSdp {
    fun build(
        logTag: String,
        rtpSocket: DatagramSocket,
        localHost: String,
        ipType: String,
        amrWbMediaCodecAvailable: Boolean,
        singtelStockOutgoingCarrier: Boolean,
        preconditionEnabled: Boolean = true,
    ): OutgoingInviteSdpOffer {
        val mediaOffer = buildMediaOffer(
            logTag = logTag,
            amrWbMediaCodecAvailable = amrWbMediaCodecAvailable,
        )
        val genericSdp = buildGenericBody(
            rtpSocket = rtpSocket,
            mediaOffer = mediaOffer,
            ipType = ipType,
            localHost = localHost,
            singtelStockOutgoingCarrier = singtelStockOutgoingCarrier,
            preconditionEnabled = preconditionEnabled,
        )
        val singtelCompactSdp = buildSingTelCompactBody(
            rtpSocket = rtpSocket,
            mediaOffer = mediaOffer,
            ipType = ipType,
            localHost = localHost,
        )
        val inviteBody = if (singtelStockOutgoingCarrier) {
            singtelCompactSdp
        } else {
            genericSdp
        }

        return OutgoingInviteSdpOffer(
            amrNbTrack = mediaOffer.amrNbTrack,
            dtmfNbTrack = mediaOffer.dtmfNbTrack,
            sdp = genericSdp,
            inviteBody = inviteBody,
        )
    }

    private fun buildMediaOffer(
        logTag: String,
        amrWbMediaCodecAvailable: Boolean,
    ): OutgoingInviteSdpMediaOffer {
        val amrNbTrack = 97
        val amrWbTrack = 98
        val dtmfNbTrack = 100
        val dtmfWbTrack = 101
        val offerAmrWb = amrWbMediaCodecAvailable
        val allTracks = if (offerAmrWb) {
            listOf(amrWbTrack, amrNbTrack, dtmfWbTrack, dtmfNbTrack)
        } else {
            listOf(amrNbTrack, dtmfNbTrack)
        }
        val offerBandwidthAs = if (offerAmrWb) {
            SipAudioCodecNegotiator.sdpBandwidthAsKbps(SipAudioCodecs.AMR_WB)
        } else {
            SipAudioCodecNegotiator.sdpBandwidthAsKbps(SipAudioCodecs.AMR_NB)
        }
        Rlog.d(
            logTag,
            "Outgoing INVITE codec offer: offerAmrWb=$offerAmrWb " +
                "tracks=$allTracks bandwidthAs=$offerBandwidthAs",
        )

        return OutgoingInviteSdpMediaOffer(
            amrNbTrack = amrNbTrack,
            amrWbTrack = amrWbTrack,
            dtmfNbTrack = dtmfNbTrack,
            dtmfWbTrack = dtmfWbTrack,
            offerAmrWb = offerAmrWb,
            allTracks = allTracks,
            offerBandwidthAs = offerBandwidthAs,
        )
    }

    private fun buildGenericBody(
        rtpSocket: DatagramSocket,
        mediaOffer: OutgoingInviteSdpMediaOffer,
        ipType: String,
        localHost: String,
        singtelStockOutgoingCarrier: Boolean,
        preconditionEnabled: Boolean,
    ): ByteArray {
        val amrNbTrack = mediaOffer.amrNbTrack
        val amrWbTrack = mediaOffer.amrWbTrack
        val dtmfNbTrack = mediaOffer.dtmfNbTrack
        val dtmfWbTrack = mediaOffer.dtmfWbTrack
        val offerAmrWb = mediaOffer.offerAmrWb
        val allTracks = mediaOffer.allTracks
        val offerBandwidthAs = mediaOffer.offerBandwidthAs

        val sdpLines = mutableListOf(
            "v=0",
            "o=- 1 2 IN $ipType $localHost",
            "s=phh voice call",
            "c=IN $ipType $localHost",
            "b=AS:$offerBandwidthAs",
            "b=RS:0",
            "b=RR:0",
            "t=0 0",
            "m=audio ${rtpSocket.localPort} RTP/AVP ${allTracks.joinToString(" ")}",
            "b=AS:$offerBandwidthAs",
            "b=RS:0",
            "b=RR:0",
            "a=ptime:20",
            "a=maxptime:240",
        )
        if (offerAmrWb) {
            sdpLines += listOf(
                "a=rtpmap:$amrWbTrack AMR-WB/16000",
                "a=fmtp:$amrWbTrack octet-align=0;mode-change-capability=2;max-red=0",
                "a=rtpmap:$dtmfWbTrack telephone-event/16000",
                "a=fmtp:$dtmfWbTrack 0-15",
            )
        }
        sdpLines += listOf(
            "a=rtpmap:$amrNbTrack AMR/8000/1",
            "a=fmtp:$amrNbTrack mode-change-capability=2;octet-align=0;max-red=0",
            "a=rtpmap:$dtmfNbTrack telephone-event/8000",
            "a=fmtp:$dtmfNbTrack 0-15",
        )
        if (preconditionEnabled) {
            sdpLines += listOf(
                "a=curr:qos local none",
                "a=curr:qos remote none",
                "a=des:qos mandatory local sendrecv",
                "a=des:qos optional remote sendrecv",
            )
        }
        sdpLines += "a=sendrecv"

        val finalOutgoingSdpLines = if (singtelStockOutgoingCarrier) {
            sdpLines.map { line -> normalizeSingTelStockOutgoingSdpLine(line) }
        } else {
            sdpLines
        }
        return (finalOutgoingSdpLines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.US_ASCII)
    }

    private fun buildSingTelCompactBody(
        rtpSocket: DatagramSocket,
        mediaOffer: OutgoingInviteSdpMediaOffer,
        ipType: String,
        localHost: String,
    ): ByteArray {
        val amrNbTrack = mediaOffer.amrNbTrack
        return listOf(
            "v=0",
            "o=- 1 2 IN $ipType $localHost",
            "s=-",
            "c=IN $ipType $localHost",
            "t=0 0",
            "m=audio ${rtpSocket.localPort} RTP/AVP $amrNbTrack",
            "a=rtpmap:$amrNbTrack AMR/8000",
            "a=fmtp:$amrNbTrack octet-align=0",
            "a=ptime:20",
            "a=sendrecv",
        ).joinToString("\r\n")
            .plus("\r\n")
            .toByteArray(Charsets.US_ASCII)
    }

    private fun normalizeSingTelStockOutgoingSdpLine(line: String): String {
        val wbRtpmap = if (
            line.startsWith("a=rtpmap:", ignoreCase = true) &&
                line.contains("AMR-WB/16000/1", ignoreCase = true)
        ) {
            line.replace("AMR-WB/16000/1", "AMR-WB/16000")
        } else {
            line
        }

        val normalizedFmtp = Regex(
            "^a=fmtp:(\\d+)\\s+.*mode-change-capability=2.*$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(wbRtpmap)?.let { match ->
            "a=fmtp:${match.groupValues[1]} max-red=0; mode-change-capability=2; octet-align=0"
        } ?: wbRtpmap

        return if (normalizedFmtp.equals("a=maxptime:240", ignoreCase = true)) {
            "a=maxptime:40"
        } else {
            normalizedFmtp
        }
    }
}
