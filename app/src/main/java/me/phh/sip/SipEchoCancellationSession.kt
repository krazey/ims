// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.os.SystemProperties
import android.telephony.Rlog

/**
 * Owns one WebRTC AEC instance for the active media generation.
 *
 * Keep processor lifetime and cross-thread access outside SipHandler. The
 * downlink playout thread feeds the exact PCM written to AudioTrack as the
 * render reference; uplink capture processing is wired separately.
 */
internal class SipEchoCancellationSession(
    private val logTag: String,
) {
    companion object {
        private const val DELAY_PROPERTY =
            "persist.phh.ims.webrtc_aec_delay_ms"
        private const val STRENGTH_PROPERTY =
            "persist.phh.ims.webrtc_aec_strength_pct"
        private const val DEFAULT_DELAY_MS = 0
        private const val MAX_DELAY_MS = 500
        private const val DEFAULT_STRENGTH_PERCENT = 175
        private const val MIN_STRENGTH_PERCENT = 100
        private const val MAX_STRENGTH_PERCENT = 300
        private const val METRICS_INTERVAL_FRAMES = 500L
        const val CAPTURE_BYPASS = -1
    }

    private val lock = Any()
    private var generation = -1
    private var sampleRateHz = 0
    private var echoCanceler: SipEchoCanceler? = null
    private var renderFrames = 0L
    private var captureFrames = 0L
    private var nextMetricsCaptureFrame = METRICS_INTERVAL_FRAMES
    private var captureDelayMs = DEFAULT_DELAY_MS
    private var capturePending = ByteArray(0)
    private var capturePendingSize = 0
    private var captureWork = ByteArray(0)

    fun start(
        sampleRateHz: Int,
        generation: Int,
    ) {
        val requestedDelayMs = SystemProperties.getInt(
            DELAY_PROPERTY,
            DEFAULT_DELAY_MS,
        )
        if (requestedDelayMs <= 0) {
            stopAll("disabled by $DELAY_PROPERTY")
            return
        }

        synchronized(lock) {
            if (
                this.generation == generation &&
                    this.sampleRateHz == sampleRateHz &&
                    echoCanceler != null
            ) {
                return
            }

            closeLocked("replaced for generation=$generation")
            val residualEchoStrengthPercent = SystemProperties.getInt(
                STRENGTH_PROPERTY,
                DEFAULT_STRENGTH_PERCENT,
            ).coerceIn(
                MIN_STRENGTH_PERCENT,
                MAX_STRENGTH_PERCENT,
            )
            val created = try {
                SipEchoCanceler(
                    sampleRateHz = sampleRateHz,
                    residualEchoStrengthPercent = residualEchoStrengthPercent,
                )
            } catch (t: Throwable) {
                Rlog.w(
                    logTag,
                    "Failed to create WebRTC AEC: " +
                        "sampleRate=$sampleRateHz generation=$generation",
                    t,
                )
                null
            } ?: return

            this.generation = generation
            this.sampleRateHz = sampleRateHz
            echoCanceler = created
            renderFrames = 0L
            captureFrames = 0L
            nextMetricsCaptureFrame = METRICS_INTERVAL_FRAMES
            captureDelayMs = requestedDelayMs.coerceIn(1, MAX_DELAY_MS)
            capturePending = ByteArray(created.frameBytes)
            capturePendingSize = 0
            captureWork = ByteArray(0)
            Rlog.w(
                logTag,
                "WebRTC AEC session started: sampleRate=$sampleRateHz " +
                    "frameBytes=${created.frameBytes} delayMs=$captureDelayMs " +
                    "strengthPct=$residualEchoStrengthPercent " +
                    "generation=$generation",
            )
        }
    }

    fun processRender(
        pcm: ByteArray,
        generation: Int,
    ) {
        synchronized(lock) {
            val processor = echoCanceler
            if (processor == null || this.generation != generation) return

            val processedBytes = try {
                processor.processRender(pcm)
            } catch (t: Throwable) {
                Rlog.w(logTag, "WebRTC AEC render processing failed", t)
                closeLocked("render processing failure")
                return
            }

            renderFrames += processedBytes / processor.frameBytes
            if (renderFrames <= 4L) {
                Rlog.d(
                    logTag,
                    "WebRTC AEC render reference: bytes=$processedBytes " +
                        "totalFrames=$renderFrames generation=$generation",
                )
            }
            if (processedBytes != pcm.size) {
                Rlog.w(
                    logTag,
                    "WebRTC AEC render left trailing PCM: " +
                        "processed=$processedBytes size=${pcm.size}",
                )
            }
        }
    }

    /**
     * Process microphone PCM before uplink gain and encoding.
     *
     * Complete 10 ms frames are written to [output]. A partial trailing frame
     * is retained until the next AudioRecord read. [CAPTURE_BYPASS] means the
     * AEC session is disabled or not active for this media generation.
     */
    fun processCapture(
        pcm: ByteArray,
        sizeBytes: Int,
        output: ByteArray,
        generation: Int,
    ): Int {
        require(sizeBytes in 0..pcm.size) {
            "Invalid capture PCM size=$sizeBytes buffer=${pcm.size}"
        }

        synchronized(lock) {
            val processor = echoCanceler
            if (processor == null || this.generation != generation) {
                return CAPTURE_BYPASS
            }

            val totalBytes = capturePendingSize + sizeBytes
            if (captureWork.size < totalBytes) {
                captureWork = ByteArray(totalBytes)
            }
            if (capturePendingSize > 0) {
                capturePending.copyInto(
                    destination = captureWork,
                    destinationOffset = 0,
                    startIndex = 0,
                    endIndex = capturePendingSize,
                )
            }
            pcm.copyInto(
                destination = captureWork,
                destinationOffset = capturePendingSize,
                startIndex = 0,
                endIndex = sizeBytes,
            )

            val processBytes = totalBytes - (totalBytes % processor.frameBytes)
            val trailingBytes = totalBytes - processBytes
            if (output.size < processBytes) {
                throw IllegalArgumentException(
                    "Capture output too small: required=$processBytes " +
                        "size=${output.size}",
                )
            }

            if (processBytes > 0) {
                val processedBytes = try {
                    processor.processCaptureInPlace(
                        pcm = captureWork,
                        offsetBytes = 0,
                        sizeBytes = processBytes,
                        delayMs = captureDelayMs,
                    )
                } catch (t: Throwable) {
                    Rlog.w(logTag, "WebRTC AEC capture processing failed", t)
                    closeLocked("capture processing failure")
                    return CAPTURE_BYPASS
                }
                if (processedBytes != processBytes) {
                    Rlog.w(
                        logTag,
                        "WebRTC AEC capture processed unexpected size: " +
                            "processed=$processedBytes expected=$processBytes",
                    )
                    closeLocked("incomplete capture processing")
                    return CAPTURE_BYPASS
                }
                captureWork.copyInto(
                    destination = output,
                    destinationOffset = 0,
                    startIndex = 0,
                    endIndex = processBytes,
                )
                captureFrames += processBytes / processor.frameBytes
                if (captureFrames <= 4L) {
                    Rlog.d(
                        logTag,
                        "WebRTC AEC capture processed: bytes=$processBytes " +
                            "totalFrames=$captureFrames delayMs=$captureDelayMs " +
                            "generation=$generation",
                    )
                }
                if (captureFrames >= nextMetricsCaptureFrame) {
                    logMetricsLocked(processor, generation)
                    nextMetricsCaptureFrame =
                        captureFrames + METRICS_INTERVAL_FRAMES
                }
            }

            if (trailingBytes > 0) {
                captureWork.copyInto(
                    destination = capturePending,
                    destinationOffset = 0,
                    startIndex = processBytes,
                    endIndex = totalBytes,
                )
            }
            capturePendingSize = trailingBytes
            return processBytes
        }
    }

    private fun logMetricsLocked(
        processor: SipEchoCanceler,
        generation: Int,
    ) {
        val metrics = try {
            processor.metrics()
        } catch (t: Throwable) {
            Rlog.d(logTag, "WebRTC AEC metrics unavailable", t)
            return
        }
        if (metrics.size < 3) return
        Rlog.d(
            logTag,
            "WebRTC AEC metrics: erl=${metrics[0]} erle=${metrics[1]} " +
                "bufferDelayMs=${metrics[2].toInt()} " +
                "captureFrames=$captureFrames generation=$generation",
        )
    }

    fun stop(
        generation: Int,
        reason: String,
    ) {
        synchronized(lock) {
            if (this.generation != generation) return
            closeLocked(reason)
        }
    }

    private fun stopAll(reason: String) {
        synchronized(lock) {
            closeLocked(reason)
        }
    }

    private fun closeLocked(reason: String) {
        val processor = echoCanceler ?: return
        echoCanceler = null
        generation = -1
        sampleRateHz = 0
        capturePendingSize = 0
        capturePending = ByteArray(0)
        captureWork = ByteArray(0)
        try {
            processor.close()
        } catch (t: Throwable) {
            Rlog.d(logTag, "WebRTC AEC close failed: $reason", t)
        }
        Rlog.d(
            logTag,
            "WebRTC AEC session stopped: reason=$reason " +
                "renderFrames=$renderFrames captureFrames=$captureFrames",
        )
        renderFrames = 0L
        captureFrames = 0L
        nextMetricsCaptureFrame = METRICS_INTERVAL_FRAMES
    }
}
