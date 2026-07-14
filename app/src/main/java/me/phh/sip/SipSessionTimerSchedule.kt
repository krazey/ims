// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

internal object SipSessionTimerSchedule {
    private const val MIN_INTERVAL_SECONDS = 90

    fun localRefreshDelayMs(intervalSeconds: Int): Long =
        intervalSeconds.coerceAtLeast(MIN_INTERVAL_SECONDS).toLong() * 500L

    fun peerExpiryDelayMs(intervalSeconds: Int): Long =
        intervalSeconds.coerceAtLeast(MIN_INTERVAL_SECONDS).toLong() * 1_000L
}
