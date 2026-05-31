//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.media.MediaCodec
import android.telephony.Rlog

object SipUplinkAudioEncoder {
    fun queuePcmInput(
        logTag: String,
        encoder: MediaCodec,
        buffer: ByteArray,
        size: Int,
        gainQ8: Int,
        logInput: Boolean,
    ) {
        if (logInput) {
            val allZero = buffer.take(size.coerceAtLeast(0)).all { it == 0.toByte() }
            Rlog.d(logTag, "AudioRecord.read nRead=$size allZero=$allZero (bufferSize=${buffer.size})")
        }

        val inBufIdx = encoder.dequeueInputBuffer(-1)
        val inBuf = encoder.getInputBuffer(inBufIdx)!!
        inBuf.clear()
        if (size > 0) {
            SipUplinkGain.applyInPlace(
                buffer = buffer,
                size = size,
                gainQ8 = gainQ8,
            )
        }
        inBuf.put(buffer, 0, size)

        // Fake timestamp but it is not appearing in the output stream anyway
        encoder.queueInputBuffer(inBufIdx, 0, size, System.nanoTime() / 1000, 0)
    }
}
