//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.Rlog
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress

object SipUplinkMediaRtpSender {
    fun sendStorageFrame(
        logTag: String,
        audioCodec: NegotiatedAudioCodec,
        payloadType: Int,
        sequenceNumber: Int,
        timestamp: Int,
        storageFrame: ByteArray,
        marker: Boolean,
        rtpSocket: DatagramSocket,
        remoteAddr: InetAddress,
        remotePort: Int,
        frameType: Int,
        frameSize: Int,
        realFrameCount: Int,
    ): Boolean {
        val packet = SipAmrRtpPayload.buildBandwidthEfficientRtpPacketFromStorageFrame(
            audioCodec = audioCodec,
            payloadType = payloadType,
            sequenceNumber = sequenceNumber,
            timestamp = timestamp,
            storageFrame = storageFrame,
            marker = marker,
        )
        if (packet == null) {
            Rlog.w(logTag, "Failed to build AMR RTP packet: codec=${audioCodec.name} ft=$frameType frameSize=$frameSize")
            return false
        }

        try {
            if (!RtpPacketSender.send(
                tag = logTag,
                rtpSocket = rtpSocket,
                bytes = packet,
                remoteAddr = remoteAddr,
                remotePort = remotePort,
                label = "RTP packet #$sequenceNumber",
            )) throw IOException("RTP send failed")
            if (realFrameCount < 10) {
                Rlog.d(logTag, "Sent RTP packet #$sequenceNumber ft=$frameType ts=$timestamp payload=${packet.drop(12).take(4).joinToString(" ") { "%02x".format(it) }}... to $remoteAddr:$remotePort")
            }
            if (realFrameCount == 0) {
                Rlog.d(logTag, "First RTP packet full hex: ${packet.joinToString(" ") { "%02x".format(it) }}")
            }
            if (sequenceNumber % 50 == 0 && realFrameCount >= 10) {
                Rlog.d(logTag, "Sent RTP packet #$sequenceNumber ft=$frameType ts=$timestamp to $remoteAddr:$remotePort")
            }
        } catch (e: Exception) {
            Rlog.e(logTag, "Failed to send RTP packet #$sequenceNumber: ${e.message}", e)
        }

        return true
    }
}
