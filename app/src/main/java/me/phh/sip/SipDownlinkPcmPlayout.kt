//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.media.AudioTrack
import android.os.SystemClock
import android.telephony.Rlog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

object SipDownlinkPcmPlayout {
    private const val DOWNLINK_UNDERRUN_CONCEALMENT_FRAMES = 6
    private const val DOWNLINK_STARTUP_CONCEALMENT_PRIME_FRAMES = 4
    private const val DOWNLINK_STARTUP_REBUFFER_FRAMES = 3
    private const val DOWNLINK_STARTUP_REBUFFER_MAX_SILENCE_FRAMES = 4
    private const val DOWNLINK_ADAPTIVE_REBUFFER_FRAMES = 2
    private const val DOWNLINK_ADAPTIVE_REBUFFER_MAX_SILENCE_FRAMES = 4
    private const val DOWNLINK_ADAPTIVE_REBUFFER_TRIGGER_FRAMES = 12

    private fun attenuatePcm16LeFrame(frame: ByteArray, gainShift: Int): ByteArray {
        val out = ByteArray(frame.size)
        var i = 0
        while (i + 1 < frame.size) {
            val sample =
                (frame[i].toInt() and 0xff) or
                    (frame[i + 1].toInt() shl 8)
            val scaled = sample.toShort().toInt() shr gainShift
            out[i] = (scaled and 0xff).toByte()
            out[i + 1] = ((scaled shr 8) and 0xff).toByte()
            i += 2
        }
        return out
    }

    fun start(
        logTag: String,
        audioTrack: AudioTrack,
        audioCodec: NegotiatedAudioCodec,
        buffers: SipDownlinkPcmPlayoutBuffers,
        callStopped: AtomicBoolean,
        callGeneration: AtomicInteger,
        generation: Int,
    ): Thread =
        thread(name = "PhhDownlinkPcmPlayout") {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                Rlog.d(logTag, "Downlink PCM playout thread priority set to urgent audio")
            } catch (t: Throwable) {
                Rlog.w(logTag, "Failed to set downlink PCM playout thread priority", t)
            }

            var fillerFrames = 0
            var startupConcealmentPrimed = false
            var realFramesSinceLastUnderrun = 0
            var startupRebuffering = true
            var startupRebufferPcmSeen = false
            var startupRebufferWaitFrames = 0
            var adaptiveRebuffering = false
            var adaptiveRebufferPcmSeen = false
            var adaptiveRebufferWaitFrames = 0
            var lastGoodPcmFrame: ByteArray? = null
            var nextWriteAtMs = SystemClock.elapsedRealtime() + 60L
            Rlog.d(logTag, "Downlink PCM playout started: frameBytes=${buffers.frameBytes} codec=${audioCodec.name}/${audioCodec.sampleRate} gen=$generation")
            try {
                while (buffers.running.get() && !callStopped.get() && callGeneration.get() == generation) {
                    val now = SystemClock.elapsedRealtime()
                    val sleepMs = nextWriteAtMs - now
                    if (sleepMs > 0L) Thread.sleep(sleepMs.coerceAtMost(40L))

                    val queuedBeforePoll = buffers.pcmQueue.size
                    if (startupRebuffering && !startupRebufferPcmSeen && queuedBeforePoll > 0) {
                        startupRebufferPcmSeen = true
                        Rlog.d(
                            logTag,
                            "Downlink PCM playout startup rebuffer first PCM " +
                                "queued=$queuedBeforePoll gen=$generation",
                        )
                    }
                    if (adaptiveRebuffering && !adaptiveRebufferPcmSeen && queuedBeforePoll > 0) {
                        adaptiveRebufferPcmSeen = true
                        Rlog.d(
                            logTag,
                            "Downlink PCM playout adaptive rebuffer first PCM " +
                                "queued=$queuedBeforePoll gen=$generation",
                        )
                    }

                    var holdForRebuffer = false
                    if (startupRebuffering) {
                        holdForRebuffer = !startupRebufferPcmSeen ||
                            (queuedBeforePoll < DOWNLINK_STARTUP_REBUFFER_FRAMES &&
                                startupRebufferWaitFrames <
                                DOWNLINK_STARTUP_REBUFFER_MAX_SILENCE_FRAMES)
                        if (holdForRebuffer) {
                            if (startupRebufferPcmSeen) startupRebufferWaitFrames++
                        } else {
                            startupRebuffering = false
                            Rlog.d(
                                logTag,
                                "Downlink PCM playout startup rebuffer complete " +
                                    "waitFrames=$startupRebufferWaitFrames " +
                                    "queued=$queuedBeforePoll gen=$generation",
                            )
                        }
                    } else if (adaptiveRebuffering) {
                        holdForRebuffer = !adaptiveRebufferPcmSeen ||
                            (queuedBeforePoll < DOWNLINK_ADAPTIVE_REBUFFER_FRAMES &&
                                adaptiveRebufferWaitFrames <
                                DOWNLINK_ADAPTIVE_REBUFFER_MAX_SILENCE_FRAMES)
                        if (holdForRebuffer) {
                            if (adaptiveRebufferPcmSeen) adaptiveRebufferWaitFrames++
                        } else {
                            adaptiveRebuffering = false
                            Rlog.d(
                                logTag,
                                "Downlink PCM playout adaptive rebuffer complete " +
                                    "waitFrames=$adaptiveRebufferWaitFrames " +
                                    "queued=$queuedBeforePoll gen=$generation",
                            )
                        }
                    }

                    val queuedPcm = if (holdForRebuffer) null else buffers.pcmQueue.poll()
                    val pcm =
                        if (queuedPcm != null) {
                            realFramesSinceLastUnderrun++
                            if (
                                !startupConcealmentPrimed &&
                                    realFramesSinceLastUnderrun >=
                                    DOWNLINK_STARTUP_CONCEALMENT_PRIME_FRAMES
                            ) {
                                startupConcealmentPrimed = true
                                Rlog.d(
                                    logTag,
                                    "Downlink PCM playout startup concealment primed " +
                                        "frames=$realFramesSinceLastUnderrun " +
                                        "queued=${buffers.pcmQueue.size} gen=$generation",
                                )
                            }
                            if (startupConcealmentPrimed) {
                                lastGoodPcmFrame = queuedPcm
                            }
                            queuedPcm
                        } else {
                            realFramesSinceLastUnderrun = 0
                            val last = lastGoodPcmFrame
                            if (
                                startupConcealmentPrimed &&
                                    last != null &&
                                    fillerFrames < DOWNLINK_UNDERRUN_CONCEALMENT_FRAMES
                            ) {
                                attenuatePcm16LeFrame(
                                    last,
                                    gainShift = (fillerFrames + 2).coerceAtMost(8),
                                )
                            } else {
                                buffers.silenceFrame
                            }
                        }

                    if (queuedPcm == null) {
                        fillerFrames++
                        if (pcm !== buffers.silenceFrame) {
                            if (fillerFrames == 1 || fillerFrames == DOWNLINK_UNDERRUN_CONCEALMENT_FRAMES) {
                                Rlog.d(
                                    logTag,
                                    "Downlink PCM playout concealed underrun frames=$fillerFrames " +
                                        "queued=${buffers.pcmQueue.size} gen=$generation",
                                )
                            }
                        } else if (fillerFrames == 1 || fillerFrames % 50 == 0) {
                            Rlog.d(logTag, "Downlink PCM playout filler frames=$fillerFrames queued=${buffers.pcmQueue.size} gen=$generation")
                        }
                        if (
                            !startupRebuffering &&
                                !adaptiveRebuffering &&
                                startupConcealmentPrimed &&
                                fillerFrames == DOWNLINK_ADAPTIVE_REBUFFER_TRIGGER_FRAMES &&
                                buffers.pcmQueue.size == 0
                        ) {
                            adaptiveRebuffering = true
                            adaptiveRebufferPcmSeen = false
                            adaptiveRebufferWaitFrames = 0
                            startupConcealmentPrimed = false
                            realFramesSinceLastUnderrun = 0
                            lastGoodPcmFrame = null
                            Rlog.d(
                                logTag,
                                "Downlink PCM playout adaptive rebuffer start " +
                                    "afterFillerFrames=$fillerFrames gen=$generation",
                            )
                        }
                    } else if (fillerFrames > 0) {
                        Rlog.d(logTag, "Downlink PCM playout recovered after fillerFrames=$fillerFrames queued=${buffers.pcmQueue.size} gen=$generation")
                        fillerFrames = 0
                    }

                    audioTrack.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                    nextWriteAtMs += 20L
                    val afterWriteMs = SystemClock.elapsedRealtime()
                    if (afterWriteMs - nextWriteAtMs > 200L) {
                        nextWriteAtMs = afterWriteMs + 20L
                    }
                }
            } catch (_: InterruptedException) {
                // Normal during call teardown.
            } catch (t: Throwable) {
                Rlog.w(logTag, "Downlink PCM playout failed", t)
            }
            Rlog.d(logTag, "Downlink PCM playout exiting: running=${buffers.running.get()} callStopped=${callStopped.get()} genMismatch=${callGeneration.get() != generation} queued=${buffers.pcmQueue.size}")
        }
}
