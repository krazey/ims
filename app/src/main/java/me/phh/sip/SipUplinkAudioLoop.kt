//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.media.AudioRecord
import android.media.MediaCodec
import android.telephony.Rlog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object SipUplinkAudioLoop {
    fun run(
        logTag: String,
        audioRecord: AudioRecord,
        bufferSize: Int,
        encoder: MediaCodec,
        audioCodec: NegotiatedAudioCodec,
        callStopped: AtomicBoolean,
        callGeneration: AtomicInteger,
        generation: Int,
        gainQ8: Int,
        nextSequenceNumber: () -> Int,
        nextTimestamp: () -> Int,
        sendFrame: (
            sequenceNumber: Int,
            timestamp: Int,
            storageFrame: ByteArray,
            marker: Boolean,
            frameType: Int,
            frameSize: Int,
            realFrameCount: Int,
        ) -> Boolean,
    ) {
        Rlog.d(
            logTag,
            "IMS uplink gain q8=$gainQ8 ${SipUplinkGain.propertySummary()}",
        )

        var firstPacket = true
        var realFrameCount = 0
        val buffer = ByteArray(bufferSize)

        while (true) {
            if (callStopped.get() || callGeneration.get() != generation) break

            val nRead = audioRecord.read(buffer, 0, buffer.size)

            if (callStopped.get() || callGeneration.get() != generation) break
            if (nRead <= 0) continue

            SipUplinkAudioEncoder.queuePcmInput(
                logTag = logTag,
                encoder = encoder,
                buffer = buffer,
                size = nRead,
                gainQ8 = gainQ8,
                logInput = realFrameCount < 5,
            )

            val drainState = SipUplinkAudioEncoder.drainEncodedOutput(
                logTag = logTag,
                encoder = encoder,
                audioCodec = audioCodec,
                firstPacket = firstPacket,
                realFrameCount = realFrameCount,
                nextSequenceNumber = nextSequenceNumber,
                nextTimestamp = nextTimestamp,
                sendFrame = sendFrame,
            )
            firstPacket = drainState.firstPacket
            realFrameCount = drainState.realFrameCount
        }
    }
}
