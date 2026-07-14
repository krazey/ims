//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.content.Context
import android.media.AudioTrack
import android.media.MediaCodec
import android.telephony.Rlog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal object SipDownlinkAudioCleanup {
    private const val INITIAL_THREAD_JOIN_MS = 500L
    private const val FINAL_THREAD_JOIN_MS = 1_500L

    private fun joinThread(logTag: String, label: String, thread: Thread, timeoutMs: Long): Boolean {
        try {
            thread.interrupt()
            thread.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            Rlog.d(logTag, "$label stop failed during decode cleanup", t)
        }
        return !thread.isAlive
    }

    private fun stopAudioTrack(logTag: String, audioTrack: AudioTrack) {
        try {
            audioTrack.stop()
        } catch (t: Throwable) {
            Rlog.d(logTag, "AudioTrack stop failed during decode cleanup", t)
        }
    }

    private fun releaseAudioTrack(logTag: String, audioTrack: AudioTrack) {
        try {
            audioTrack.release()
        } catch (t: Throwable) {
            Rlog.d(logTag, "AudioTrack release failed during decode cleanup", t)
        }
    }

    private fun releaseDecoder(logTag: String, decoder: MediaCodec) {
        try {
            decoder.stop()
        } catch (t: Throwable) {
            Rlog.d(logTag, "Decoder stop failed during decode cleanup", t)
        }
        try {
            decoder.release()
        } catch (t: Throwable) {
            Rlog.d(logTag, "Decoder release failed during decode cleanup", t)
        }
    }

    fun cleanupStartupFailure(
        logTag: String,
        context: Context,
        audioTrack: AudioTrack?,
        decoder: MediaCodec?,
        decoderWorker: SipDownlinkAudioDecoderWorker?,
        playoutBuffers: SipDownlinkPcmPlayoutBuffers?,
        playoutThread: Thread?,
        previousAudioMode: Int,
    ) {
        val decoderStopped = decoderWorker?.stop() ?: true
        playoutBuffers?.running?.set(false)

        var playoutStopped = playoutThread == null ||
            joinThread(logTag, "Downlink playout", playoutThread, INITIAL_THREAD_JOIN_MS)
        if (!playoutStopped && audioTrack != null) {
            stopAudioTrack(logTag, audioTrack)
            playoutStopped = joinThread(
                logTag,
                "Downlink playout",
                playoutThread!!,
                FINAL_THREAD_JOIN_MS,
            )
        }

        if (audioTrack != null) {
            if (playoutStopped) {
                stopAudioTrack(logTag, audioTrack)
                releaseAudioTrack(logTag, audioTrack)
            } else {
                Rlog.e(logTag, "Not releasing AudioTrack still used by downlink playout")
            }
        }
        if (decoder != null) {
            if (decoderStopped) {
                releaseDecoder(logTag, decoder)
            } else {
                Rlog.e(logTag, "Not releasing MediaCodec still used by decoder worker")
            }
        }

        SipAudioModeRestorer.restoreAfterImsCall(
            logTag = logTag,
            context = context,
            reason = "downlink startup failure",
            previousMode = previousAudioMode,
        )
    }

    fun cleanup(
        logTag: String,
        context: Context,
        audioTrack: AudioTrack,
        decoder: MediaCodec,
        decoderWorker: SipDownlinkAudioDecoderWorker,
        playoutBuffers: SipDownlinkPcmPlayoutBuffers,
        playoutThread: Thread,
        callStopped: AtomicBoolean,
        callGeneration: AtomicInteger,
        generation: Int,
        receivedCount: Int,
        previousAudioMode: Int,
    ) {
        val decoderStopped = decoderWorker.stop()

        playoutBuffers.running.set(false)
        var playoutStopped = joinThread(
            logTag,
            "Downlink playout",
            playoutThread,
            INITIAL_THREAD_JOIN_MS,
        )
        if (!playoutStopped) {
            // A blocking AudioTrack.write may not react to interruption. Stop
            // the track to unblock it, then wait once more before releasing.
            stopAudioTrack(logTag, audioTrack)
            playoutStopped = joinThread(
                logTag,
                "Downlink playout",
                playoutThread,
                FINAL_THREAD_JOIN_MS,
            )
        }

        if (playoutStopped) {
            stopAudioTrack(logTag, audioTrack)
            releaseAudioTrack(logTag, audioTrack)
        } else {
            Rlog.e(logTag, "Not releasing AudioTrack still used by downlink playout")
        }
        if (decoderStopped) {
            releaseDecoder(logTag, decoder)
        } else {
            Rlog.e(logTag, "Not releasing MediaCodec still used by decoder worker")
        }
        val stopped = callStopped.get()
        val genMismatch = callGeneration.get() != generation
        if (genMismatch) {
            Rlog.i(
                logTag,
                "Skipping audio mode restore from stale decode media generation: " +
                    "callStopped=$stopped genMismatch=$genMismatch received=$receivedCount",
            )
        } else {
            SipAudioModeRestorer.restoreAfterImsCall(
                logTag = logTag,
                context = context,
                reason = "decode thread cleanup",
                previousMode = previousAudioMode,
            )
        }
        Rlog.d(
            logTag,
            "Decode thread cleanup complete: callStopped=$stopped " +
                "genMismatch=$genMismatch received=$receivedCount",
        )
    }
}
