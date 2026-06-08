//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

internal data class SipAmrStorageFrame(
    val ft: Int,
    val q: Int,
    val codecFrame: ByteArray,
)

internal object SipAmrRtpPayload {
    private val amrNbSpeechBitsByFt = intArrayOf(
        95,  // 4.75
        103, // 5.15
        118, // 5.90
        134, // 6.70
        148, // 7.40
        159, // 7.95
        204, // 10.2
        244, // 12.2
        39,  // SID
    )

    private val amrWbSpeechBitsByFt = intArrayOf(
        132, // 6.60
        177, // 8.85
        253, // 12.65
        285, // 14.25
        317, // 15.85
        365, // 18.25
        397, // 19.85
        461, // 23.05
        477, // 23.85
        40,  // SID
    )

    private fun speechBitsForFt(audioCodec: NegotiatedAudioCodec, ft: Int): Int? =
        when (audioCodec.sdpCodecName) {
            "AMR" -> amrNbSpeechBitsByFt.getOrNull(ft)
            "AMR-WB" -> amrWbSpeechBitsByFt.getOrNull(ft)
            else -> null
        }

    private fun rtpPayloadOffset(packet: ByteArray, packetLength: Int): Int? {
        if (packetLength < 12) return null

        val csrcCount = packet[0].toInt() and 0x0f
        var offset = 12 + csrcCount * 4
        if (packetLength < offset) return null

        val hasExtension = (packet[0].toInt() and 0x10) != 0
        if (hasExtension) {
            if (packetLength < offset + 4) return null
            val extensionLengthWords =
                ((packet[offset + 2].toInt() and 0xff) shl 8) or
                    (packet[offset + 3].toInt() and 0xff)
            offset += 4 + extensionLengthWords * 4
            if (packetLength < offset) return null
        }

        return offset
    }

    private class BitReader(
        private val data: ByteArray,
        private val startBit: Int,
        private val endBit: Int,
    ) {
        private var bit = startBit

        fun readBit(): Int? {
            if (bit >= endBit) return null
            val byteValue = data[bit / 8].toInt() and 0xff
            val value = (byteValue shr (7 - (bit % 8))) and 1
            bit++
            return value
        }

        fun readBits(count: Int): Int? {
            var value = 0
            repeat(count) {
                value = (value shl 1) or (readBit() ?: return null)
            }
            return value
        }
    }

    fun storageFrameSizeBytes(audioCodec: NegotiatedAudioCodec, ft: Int): Int? {
        val speechBits = speechBitsForFt(audioCodec, ft) ?: return null
        return 1 + ((speechBits + 7) / 8)
    }

    private fun buildRtpHeader(
        payloadType: Int,
        sequenceNumber: Int,
        timestamp: Int,
        marker: Boolean = false,
    ): ByteArray =
        byteArrayOf(
            0x80.toByte(),
            ((if (marker) 0x80 else 0) or (payloadType and 0x7f)).toByte(),
            (sequenceNumber shr 8).toByte(),
            (sequenceNumber and 0xff).toByte(),
            (timestamp shr 24).toByte(),
            ((timestamp shr 16) and 0xff).toByte(),
            ((timestamp shr 8) and 0xff).toByte(),
            (timestamp and 0xff).toByte(),
            0x03,
            0x00,
            0xd2.toByte(),
            0x00,
        )

    fun buildNoDataRtpPacket(
        audioCodec: NegotiatedAudioCodec,
        payloadType: Int,
        sequenceNumber: Int,
        timestamp: Int,
        marker: Boolean = false,
    ): ByteArray {
        // Preserve the current AMR-NB behavior exactly:
        //   CMR=7 (12.2 kbps), F=0, FT=15 (No Data), Q=1.
        //
        // AMR-WB is not selected yet. When it is enabled, this helper is the
        // single place to adjust codec-mode request/no-data framing if needed.
        val noDataPayload = when (audioCodec.sdpCodecName) {
            // Preserve current AMR-NB behavior: CMR=7 requests 12.2 kbit/s.
            "AMR" -> byteArrayOf(0x77.toByte(), 0xc0.toByte())
            // For AMR-WB, do not request a specific codec mode during no-data.
            // CMR=15, F=0, FT=15, Q=1.
            "AMR-WB" -> byteArrayOf(0xf7.toByte(), 0xc0.toByte())
            else -> byteArrayOf(0x77.toByte(), 0xc0.toByte())
        }

        return buildRtpHeader(
            payloadType = payloadType,
            sequenceNumber = sequenceNumber,
            timestamp = timestamp,
            marker = marker,
        ) + noDataPayload
    }

    fun buildBandwidthEfficientRtpPacketFromStorageFrame(
        audioCodec: NegotiatedAudioCodec,
        payloadType: Int,
        sequenceNumber: Int,
        timestamp: Int,
        storageFrame: ByteArray,
        marker: Boolean = false,
    ): ByteArray? {
        if (storageFrame.isEmpty()) return null

        // Android MediaCodec emits AMR storage-format frames:
        //   byte 0 = frame header: [0][FT(4)][Q][P][P]
        //   bytes 1..N = speech bits, MSB-first, octet padded.
        //
        // Build RFC 4867 bandwidth-efficient single-frame payload:
        //   CMR(4), F(1), FT(4), Q(1), speech bits...
        val ft = (storageFrame[0].toUByte().toInt() shr 3) and 0x0f
        val q = (storageFrame[0].toUByte().toInt() shr 2) and 0x01
        val frameSize = storageFrameSizeBytes(audioCodec, ft) ?: return null
        if (storageFrame.size < frameSize) return null

        val cmr = 0x0f // no codec-mode request
        val f = 0      // one frame only

        val payloadByte0 = (cmr shl 4) or (f shl 3) or (ft shr 1)
        val payloadByte1 = ((ft and 1) shl 7) or
            (q shl 6) or
            (storageFrame[1].toUByte().toInt() shr 2)

        val payloadRest = (1 until frameSize - 1).map { i ->
            val lo = (storageFrame[i].toUByte().toInt() and 0x03) shl 6
            val hi = (storageFrame[i + 1].toUByte().toInt() shr 2) and 0x3f
            lo or hi
        }.map { it.toByte() }.toByteArray()

        val payload = byteArrayOf(payloadByte0.toByte(), payloadByte1.toByte()) + payloadRest

        return buildRtpHeader(
            payloadType = payloadType,
            sequenceNumber = sequenceNumber,
            timestamp = timestamp,
            marker = marker,
        ) + payload
    }

    fun storageFrameFromBandwidthEfficientRtp(
        audioCodec: NegotiatedAudioCodec,
        packet: ByteArray,
        packetLength: Int,
    ): SipAmrStorageFrame? {
        val payloadOffset = rtpPayloadOffset(packet, packetLength) ?: return null
        val payloadBitsStart = payloadOffset * 8
        val payloadBitsEnd = packetLength * 8
        val reader = BitReader(packet, payloadBitsStart, payloadBitsEnd)

        // RFC 4867 bandwidth-efficient, single-channel:
        //   CMR(4), TOC: F(1), FT(4), Q(1), speech bits...
        reader.readBits(4) ?: return null // CMR
        val follow = reader.readBit() ?: return null
        if (follow != 0) {
            // The current media path only supports one AMR frame per RTP packet.
            return null
        }

        val ft = reader.readBits(4) ?: return null
        val q = reader.readBit() ?: return null
        val speechBits = speechBitsForFt(audioCodec, ft) ?: return null

        val frameSize = 1 + ((speechBits + 7) / 8)
        val storage = ByteArray(frameSize)
        storage[0] = (((ft and 0x0f) shl 3) or ((q and 0x01) shl 2)).toByte()

        for (i in 0 until speechBits) {
            val speechBit = reader.readBit() ?: return null
            if (speechBit == 0) continue
            val byteIndex = 1 + i / 8
            val bitIndex = 7 - (i % 8)
            storage[byteIndex] = (storage[byteIndex].toInt() or (1 shl bitIndex)).toByte()
        }

        return SipAmrStorageFrame(
            ft = ft,
            q = q,
            codecFrame = storage,
        )
    }
}
