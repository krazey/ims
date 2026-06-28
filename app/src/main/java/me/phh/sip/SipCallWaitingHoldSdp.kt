// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import java.net.Inet6Address
import java.net.InetAddress

internal object SipCallWaitingHoldSdp {
    fun build(
        call: SipHandler.Call,
        localAddress: InetAddress,
        mySip: String,
        myTel: String,
        holdDirection: String = "sendonly",
    ): ByteArray {
        val ipType = if (localAddress is Inet6Address) "IP6" else "IP4"
        val sessionVersion = call.localSdpVersion.incrementAndGet().coerceAtLeast(3)
        val owner = mySip
            .removePrefix("sip:")
            .substringBefore('@')
            .trim('<', '>', ' ')
            .ifBlank { myTel.ifBlank { "phh" } }
        val bandwidth = SipAudioCodecNegotiator.sdpBandwidthAsKbps(call.audioCodec)
        val speechFmtp = SipAudioCodecNegotiator.defaultSpeechFmtpAnswer(call.amrTrack, call.audioCodec)
        val lines = listOf(
            "v=0",
            "o=$owner 1 $sessionVersion IN $ipType ${localAddress.hostAddress}",
            "s=phh voice call hold",
            "c=IN $ipType ${localAddress.hostAddress}",
            "b=AS:$bandwidth",
            "b=RS:0",
            "b=RR:0",
            "t=0 0",
            "m=audio ${call.rtpSocket.localPort} RTP/AVP ${call.amrTrack} ${call.dtmfTrack}",
            "b=AS:$bandwidth",
            "b=RS:0",
            "b=RR:0",
            "a=${call.amrTrackDesc}",
            "a=ptime:20",
            "a=maxptime:240",
            "a=${call.dtmfTrackDesc}",
            "a=$speechFmtp",
            "a=fmtp:${call.dtmfTrack} 0-15",
            "a=$holdDirection",
        )
        return (lines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.US_ASCII)
    }
}
