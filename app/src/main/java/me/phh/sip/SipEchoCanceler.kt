// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

/**
 * Native WebRTC acoustic echo canceller for mono PCM16 call audio.
 *
 * 8 kHz call audio is resampled internally to 16 kHz for AEC3. Both
 * render and capture methods consume complete 10 ms frames. The render
 * stream supplies the far-end reference, while capture audio is processed in
 * place before encoding.
 */
internal class SipEchoCanceler(
    val sampleRateHz: Int,
    residualEchoStrengthPercent: Int,
) : AutoCloseable {
    companion object {
        private const val PCM_SAMPLE_BYTES = 2
        private val SUPPORTED_SAMPLE_RATES = setOf(8_000, 16_000, 32_000, 48_000)

        init {
            System.loadLibrary("webrtc_aec_jni")
        }
    }

    val frameSamples: Int = sampleRateHz / 100
    val frameBytes: Int = frameSamples * PCM_SAMPLE_BYTES

    private var nativeHandle: Long

    init {
        require(sampleRateHz in SUPPORTED_SAMPLE_RATES) {
            "Unsupported WebRTC AEC sample rate: $sampleRateHz"
        }
        nativeHandle = nativeCreate(
            sampleRateHz = sampleRateHz,
            residualEchoStrengthPercent = residualEchoStrengthPercent,
        )
        check(nativeHandle != 0L) {
            "Failed to create WebRTC AEC for $sampleRateHz Hz"
        }
    }

    /**
     * Feed complete 10 ms render frames as the far-end echo reference.
     *
     * Returns the number of consumed bytes. An incomplete trailing frame is
     * left untouched for the caller to retain until more PCM is available.
     */
    fun processRender(
        pcm: ByteArray,
        offsetBytes: Int = 0,
        sizeBytes: Int = pcm.size - offsetBytes,
    ): Int = process(
        pcm = pcm,
        offsetBytes = offsetBytes,
        sizeBytes = sizeBytes,
        capture = false,
        delayMs = 0,
    )

    /**
     * Cancel render echo from complete 10 ms capture frames in place.
     *
     * [delayMs] is the configured render-to-capture path delay.
     */
    fun processCaptureInPlace(
        pcm: ByteArray,
        offsetBytes: Int = 0,
        sizeBytes: Int = pcm.size - offsetBytes,
        delayMs: Int,
    ): Int = process(
        pcm = pcm,
        offsetBytes = offsetBytes,
        sizeBytes = sizeBytes,
        capture = true,
        delayMs = delayMs.coerceAtLeast(0),
    )

    @Synchronized
    private fun process(
        pcm: ByteArray,
        offsetBytes: Int,
        sizeBytes: Int,
        capture: Boolean,
        delayMs: Int,
    ): Int {
        require(offsetBytes >= 0 && sizeBytes >= 0) {
            "Negative PCM range: offset=$offsetBytes size=$sizeBytes"
        }
        require(offsetBytes <= pcm.size - sizeBytes) {
            "PCM range exceeds buffer: offset=$offsetBytes size=$sizeBytes " +
                "buffer=${pcm.size}"
        }

        val handle = nativeHandle
        check(handle != 0L) { "WebRTC AEC is closed" }
        val processedBytes = nativeProcess(
            handle = handle,
            pcm = pcm,
            offsetBytes = offsetBytes,
            sizeBytes = sizeBytes,
            capture = capture,
            delayMs = delayMs,
        )
        check(processedBytes >= 0) {
            "WebRTC AEC native processing failed: error=$processedBytes"
        }
        return processedBytes
    }

    @Synchronized
    fun metrics(): DoubleArray {
        val handle = nativeHandle
        check(handle != 0L) { "WebRTC AEC is closed" }
        return nativeGetMetrics(handle)
    }

    @Synchronized
    override fun close() {
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) {
            nativeDestroy(handle)
        }
    }

    private external fun nativeCreate(
        sampleRateHz: Int,
        residualEchoStrengthPercent: Int,
    ): Long

    private external fun nativeProcess(
        handle: Long,
        pcm: ByteArray,
        offsetBytes: Int,
        sizeBytes: Int,
        capture: Boolean,
        delayMs: Int,
    ): Int

    private external fun nativeGetMetrics(handle: Long): DoubleArray

    private external fun nativeDestroy(handle: Long)
}
