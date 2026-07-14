// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Test

class SipSessionTimerScheduleTest {
    @Test
    fun `peer watchdog waits for negotiated expiration`() {
        require(SipSessionTimerSchedule.peerExpiryDelayMs(90) == 90_000L)
        require(SipSessionTimerSchedule.peerExpiryDelayMs(1800) == 1_800_000L)
    }

    @Test
    fun `local refresher runs at half interval`() {
        require(SipSessionTimerSchedule.localRefreshDelayMs(90) == 45_000L)
        require(SipSessionTimerSchedule.localRefreshDelayMs(1800) == 900_000L)
    }
}
