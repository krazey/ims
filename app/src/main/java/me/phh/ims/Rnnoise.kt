//SPDX-License-Identifier: GPL-2.0
package me.phh.ims

class Rnnoise : AutoCloseable {
    companion object {
        const val SAMPLE_RATE_HZ = 48_000
        private const val SAMPLE_BYTES = 2

        init {
            System.loadLibrary("rnnoise_jni")
        }
    }

    private external fun init(): Long
    private external fun processFrameInPlace(
        state: Long,
        pcm: ByteArray,
        offsetBytes: Int,
    ): Float
    private external fun destroy(state: Long)
    external fun getFrameSize(): Int

    private var state: Long = init()
    val frameSamples: Int = getFrameSize()
    val frameBytes: Int = frameSamples * SAMPLE_BYTES

    init {
        check(state != 0L) { "Failed to create RNNoise state" }
        check(frameSamples > 0) { "Invalid RNNoise frame size" }
    }

    /**
     * Denoise complete 48 kHz mono PCM16 frames in place.
     *
     * Returns the number of processed bytes. Any incomplete trailing frame is
     * left untouched for the caller to buffer until more samples arrive.
     */
    @Synchronized
    fun processInPlace(
        pcm: ByteArray,
        sizeBytes: Int = pcm.size,
    ): Int {
        val currentState = state
        check(currentState != 0L) { "RNNoise is closed" }
        require(sizeBytes in 0..pcm.size) {
            "Invalid PCM size $sizeBytes for buffer size ${pcm.size}"
        }

        var offsetBytes = 0
        while (offsetBytes + frameBytes <= sizeBytes) {
            val voiceProbability = processFrameInPlace(
                currentState,
                pcm,
                offsetBytes,
            )
            check(voiceProbability >= 0.0f) {
                "RNNoise native processing failed at offset=$offsetBytes"
            }
            offsetBytes += frameBytes
        }
        return offsetBytes
    }

    @Synchronized
    override fun close() {
        val oldState = state
        state = 0L
        if (oldState != 0L) {
            destroy(oldState)
        }
    }
}
