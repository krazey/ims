//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.media.MediaCodec
import android.telephony.Rlog
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

object SipDownlinkAudioDecoder {
    private const val DOWNLINK_DECODER_INPUT_TIMEOUT_US = 5_000L
    private const val DOWNLINK_DECODER_FIRST_OUTPUT_TIMEOUT_US = 5_000L
    private val decoderInputDropCount = AtomicInteger(0)

    private fun offerPcmFrame(
        logTag: String,
        pcmQueue: ArrayBlockingQueue<ByteArray>,
        pcm: ByteArray,
    ) {
        if (!pcmQueue.offer(pcm)) {
            pcmQueue.poll()
            if (!pcmQueue.offer(pcm)) {
                Rlog.w(logTag, "Downlink PCM queue still full after dropping oldest frame")
            }
        }
    }

    private fun drainDecoderOutput(
        logTag: String,
        decoder: MediaCodec,
        pcmQueue: ArrayBlockingQueue<ByteArray>,
        firstTimeoutUs: Long,
    ) {
        val outBufInfo = MediaCodec.BufferInfo()
        var drainTimeoutUs = firstTimeoutUs
        while (true) {
            val outBufIndex = decoder.dequeueOutputBuffer(outBufInfo, drainTimeoutUs)
            drainTimeoutUs = 0L
            if (outBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Rlog.d(logTag, "Decoder output format changed")
                continue
            }
            if (outBufIndex < 0) break

            val outBuf = decoder.getOutputBuffer(outBufIndex)!!
            val pcm = ByteArray(outBufInfo.size)
            outBuf.position(outBufInfo.offset)
            outBuf.limit(outBufInfo.offset + outBufInfo.size)
            outBuf.get(pcm)
            offerPcmFrame(logTag, pcmQueue, pcm)
            decoder.releaseOutputBuffer(outBufIndex, false)
        }
    }

    fun queueCodecFrameAndDrainPcm(
        logTag: String,
        decoder: MediaCodec,
        codecFrame: ByteArray,
        pcmQueue: ArrayBlockingQueue<ByteArray>,
    ) {
        // Keep RTP receive realtime-friendly.  First drain any pending output so
        // the codec has a chance to free input buffers, but never block the RTP
        // receive thread indefinitely waiting for MediaCodec.
        drainDecoderOutput(
            logTag = logTag,
            decoder = decoder,
            pcmQueue = pcmQueue,
            firstTimeoutUs = 0L,
        )

        val inBufIndex = decoder.dequeueInputBuffer(DOWNLINK_DECODER_INPUT_TIMEOUT_US)
        if (inBufIndex < 0) {
            val dropped = decoderInputDropCount.incrementAndGet()
            if (dropped <= 5 || dropped % 50 == 0) {
                Rlog.w(
                    logTag,
                    "Downlink decoder input unavailable; dropping codec frame " +
                        "dropCount=$dropped codecBytes=${codecFrame.size} queued=${pcmQueue.size}",
                )
            }
            return
        }
        decoderInputDropCount.set(0)

        val inBuf = decoder.getInputBuffer(inBufIndex)!!
        inBuf.clear()
        inBuf.put(codecFrame)
        decoder.queueInputBuffer(inBufIndex, 0, codecFrame.size, 0, 0)

        // Some AMR decoder implementations do not produce output on an immediate
        // zero-timeout poll.  Give them a tiny bounded budget, then drain anything
        // else already available without delaying the RTP receive loop further.
        drainDecoderOutput(
            logTag = logTag,
            decoder = decoder,
            pcmQueue = pcmQueue,
            firstTimeoutUs = DOWNLINK_DECODER_FIRST_OUTPUT_TIMEOUT_US,
        )
    }
}
