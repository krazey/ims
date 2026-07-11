//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.media.MediaCodec
import android.telephony.Rlog
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal class SipDownlinkAudioDecoderWorker(
    private val logTag: String,
    private val decoder: MediaCodec,
    private val pcmQueue: ArrayBlockingQueue<ByteArray>,
    private val callStopped: AtomicBoolean,
    private val callGeneration: AtomicInteger,
    private val generation: Int,
    encodedQueueCapacity: Int = 12,
) {
    companion object {
        private const val DECODER_INPUT_TIMEOUT_US = 5_000L
        private const val DECODER_OUTPUT_TIMEOUT_US = 5_000L
        private const val FRAME_DURATION_US = 20_000L
    }

    private val running = AtomicBoolean(true)
    private val encodedQueue = ArrayBlockingQueue<ByteArray>(encodedQueueCapacity)
    private val encodedDropCount = AtomicInteger(0)
    private val decoderInputDropCount = AtomicInteger(0)

    private val workerThread = thread(name = "PhhDownlinkAudioDecoder") {
        runDecoderLoop()
    }

    fun offer(codecFrame: ByteArray) {
        if (!running.get()) return
        if (encodedQueue.offer(codecFrame)) return

        encodedQueue.poll()
        if (!encodedQueue.offer(codecFrame)) {
            Rlog.w(logTag, "Downlink encoded queue still full after dropping oldest frame")
            return
        }

        val dropped = encodedDropCount.incrementAndGet()
        if (dropped <= 5 || dropped % 50 == 0) {
            Rlog.w(
                logTag,
                "Downlink encoded queue full; dropped oldest codec frame " +
                    "dropCount=$dropped queued=${encodedQueue.size}",
            )
        }
    }

    fun stop() {
        running.set(false)
        workerThread.interrupt()
        try {
            workerThread.join(500L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (workerThread.isAlive) {
            Rlog.w(logTag, "Downlink decoder worker did not stop promptly")
        }
    }

    private fun offerPcmFrame(pcm: ByteArray) {
        if (!pcmQueue.offer(pcm)) {
            pcmQueue.poll()
            if (!pcmQueue.offer(pcm)) {
                Rlog.w(logTag, "Downlink PCM queue still full after dropping oldest frame")
            }
        }
    }

    private fun drainDecoderOutput(firstTimeoutUs: Long) {
        val outBufInfo = MediaCodec.BufferInfo()
        var drainTimeoutUs = firstTimeoutUs
        while (running.get()) {
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
            offerPcmFrame(pcm)
            decoder.releaseOutputBuffer(outBufIndex, false)
        }
    }

    private fun runDecoderLoop() {
        try {
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_URGENT_AUDIO,
            )
            Rlog.d(logTag, "Downlink decoder worker priority set to urgent audio")
        } catch (t: Throwable) {
            Rlog.w(logTag, "Failed to set downlink decoder worker priority", t)
        }

        var presentationTimeUs = 0L
        try {
            while (
                running.get() &&
                    !callStopped.get() &&
                    callGeneration.get() == generation
            ) {
                drainDecoderOutput(firstTimeoutUs = 0L)

                val codecFrame = encodedQueue.poll(10L, TimeUnit.MILLISECONDS)
                    ?: continue

                val inBufIndex = decoder.dequeueInputBuffer(
                    DECODER_INPUT_TIMEOUT_US,
                )
                if (inBufIndex < 0) {
                    val dropped = decoderInputDropCount.incrementAndGet()
                    if (dropped <= 5 || dropped % 50 == 0) {
                        Rlog.w(
                            logTag,
                            "Downlink decoder input unavailable; dropping codec frame " +
                                "dropCount=$dropped codecBytes=${codecFrame.size} " +
                                "queued=${encodedQueue.size}",
                        )
                    }
                    continue
                }
                decoderInputDropCount.set(0)

                val inBuf = decoder.getInputBuffer(inBufIndex)!!
                inBuf.clear()
                inBuf.put(codecFrame)
                decoder.queueInputBuffer(
                    inBufIndex,
                    0,
                    codecFrame.size,
                    presentationTimeUs,
                    0,
                )
                presentationTimeUs += FRAME_DURATION_US

                drainDecoderOutput(
                    firstTimeoutUs = DECODER_OUTPUT_TIMEOUT_US,
                )
            }
        } catch (_: InterruptedException) {
            // Normal during call teardown.
        } catch (t: Throwable) {
            if (running.get()) {
                Rlog.w(logTag, "Downlink decoder worker failed", t)
            }
        }

        Rlog.d(
            logTag,
            "Downlink decoder worker exiting: running=${running.get()} " +
                "callStopped=${callStopped.get()} " +
                "genMismatch=${callGeneration.get() != generation} " +
                "encodedQueued=${encodedQueue.size} pcmQueued=${pcmQueue.size}",
        )
    }
}
