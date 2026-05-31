//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.Rlog
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object SipUplinkSilencePacer {
    fun sendUntilCallStarted(
        logTag: String,
        callStarted: AtomicBoolean,
        callStopped: AtomicBoolean,
        callGeneration: AtomicInteger,
        generation: Int,
        nextSequenceNumber: () -> Int,
        nextTimestamp: () -> Int,
        totalPacketsSent: () -> Int,
        sendPacket: (sequenceNumber: Int, timestamp: Int) -> Boolean,
        cleanupOnExit: () -> Unit,
    ): Boolean {
        while (!callStarted.get()) {
            if (callStopped.get() || callGeneration.get() != generation) {
                Rlog.d(
                    logTag,
                    "Silence loop exiting early: callStopped=${callStopped.get()}, " +
                        "genMismatch=${callGeneration.get() != generation}"
                )
                cleanupOnExit()
                return false
            }

            val sequenceNumber = nextSequenceNumber()
            val timestamp = nextTimestamp()
            Thread.sleep(20)

            try {
                if (!sendPacket(sequenceNumber, timestamp)) {
                    throw IOException("RTP send failed")
                }
            } catch (e: Exception) {
                Rlog.w(logTag, "Silence RTP send failed, stopping encode thread: ${e.message}", e)
                cleanupOnExit()
                return false
            }
        }

        Rlog.d(logTag, "Silence loop exited after ${totalPacketsSent()} packets, starting real encoding")
        return true
    }
}
