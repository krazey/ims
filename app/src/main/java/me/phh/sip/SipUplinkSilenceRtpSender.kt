//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import java.net.DatagramSocket
import java.net.InetAddress

object SipUplinkSilenceRtpSender {
    fun sendNoDataPacket(
        logTag: String,
        audioCodec: NegotiatedAudioCodec,
        payloadType: Int,
        sequenceNumber: Int,
        timestamp: Int,
        rtpSocket: DatagramSocket,
        remoteAddr: InetAddress,
        remotePort: Int,
        label: String,
    ): Boolean {
        val packet = SipAmrRtpPayload.buildNoDataRtpPacket(
            audioCodec = audioCodec,
            payloadType = payloadType,
            sequenceNumber = sequenceNumber,
            timestamp = timestamp,
        )
        return RtpPacketSender.send(
            tag = logTag,
            rtpSocket = rtpSocket,
            bytes = packet,
            remoteAddr = remoteAddr,
            remotePort = remotePort,
            label = label,
        )
    }
}
