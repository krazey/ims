// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.Rlog
import android.telephony.TelephonyManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal class ImsNetworkRequestRestarter(
    private val tag: String,
    private val telephonyManager: TelephonyManager,
    private val requestImsNetwork: () -> Unit,
) {
    private val scheduled = AtomicBoolean(false)
    private val generation = AtomicInteger(0)

    fun invalidate(reason: String) {
        val newGeneration = generation.incrementAndGet()
        scheduled.set(false)
        Rlog.d(
            tag,
            "Invalidated pending IMS network request restarts: " +
                "$reason generation=$newGeneration",
        )
    }

    fun schedule(reason: String, initialDelayMs: Long = 12_000L) {
        if (!scheduled.compareAndSet(false, true)) {
            Rlog.w(tag, "IMS network request restart already scheduled, ignore: $reason")
            return
        }

        val requestGeneration = generation.get()

        thread {
            try {
                var delayMs = initialDelayMs

                while (true) {
                    Rlog.w(
                        tag,
                        "Will request IMS network after ${delayMs}ms if RAT is IMS-capable: $reason",
                    )
                    Thread.sleep(delayMs)

                    if (requestGeneration != generation.get()) {
                        Rlog.w(
                            tag,
                            "Skipping stale IMS network request restart: $reason " +
                                "requestGeneration=$requestGeneration " +
                                "currentGeneration=${generation.get()}",
                        )
                        return@thread
                    }

                    if (ImsNetworkState.isRatReadyForImsNetworkRequest(tag, telephonyManager)) {
                        Rlog.w(tag, "Re-requesting IMS network after RAT recovered: $reason")
                        requestImsNetwork()
                        return@thread
                    }

                    delayMs = 5_000L
                }
            } catch (t: Throwable) {
                Rlog.e(tag, "IMS network request restart failed: $reason", t)
            } finally {
                if (requestGeneration == generation.get()) {
                    scheduled.set(false)
                }
            }
        }
    }
}
