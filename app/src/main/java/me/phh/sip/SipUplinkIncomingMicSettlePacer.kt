//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.Rlog
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object SipUplinkIncomingMicSettlePacer {
    fun delayBeforeMicStart(
        logTag: String,
        delayMs: Long,
        reason: String,
        callStopped: AtomicBoolean,
        callGeneration: AtomicInteger,
        generation: Int,
        nextSequenceNumber: () -> Int,
        nextTimestamp: () -> Int,
        sendPacket: (sequenceNumber: Int, timestamp: Int) -> Boolean,
        cleanupOnExit: () -> Unit,
    ): Boolean {
        val settleDeadline = System.currentTimeMillis() + delayMs
        var settlePackets = 0
        Rlog.d(
            logTag,
            "Delaying incoming AudioRecord start by ${delayMs}ms after ACK: reason=$reason gen=$generation",
        )

        while (System.currentTimeMillis() < settleDeadline) {
            if (callStopped.get() || callGeneration.get() != generation) {
                Rlog.d(
                    logTag,
                    "Incoming mic delay exiting early: callStopped=${callStopped.get()} " +
                        "genMismatch=${callGeneration.get() != generation}",
                )
                cleanupOnExit()
                return false
            }

            val sequenceNumber = nextSequenceNumber()
            val timestamp = nextTimestamp()
            try {
                if (!sendPacket(sequenceNumber, timestamp)) {
                    throw IOException("RTP send failed")
                }
            } catch (e: Exception) {
                Rlog.w(logTag, "Incoming RTP settle silence failed, stopping encode thread: ${e.message}", e)
                cleanupOnExit()
                return false
            }

            settlePackets++
            Thread.sleep(20)
        }

        Rlog.d(logTag, "Incoming AudioRecord delay complete after $settlePackets packets; starting real encoding")
        return true
    }
}
