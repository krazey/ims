// SPDX-License-Identifier: GPL-2.0
package me.phh.ims

import org.junit.Test

class OutgoingCallFailureCallbackPolicyTest {
    @Test
    fun setupFailureBeforeProgressUsesInitiatingFailed() {
        require(
            OutgoingCallFailureCallbackPolicy.select(
                callStartFailed = true,
                progressReported = false,
            ) == OutgoingCallFailureCallback.INITIATING_FAILED,
        )
    }

    @Test
    fun rejectionAfterProgressUsesTerminated() {
        require(
            OutgoingCallFailureCallbackPolicy.select(
                callStartFailed = true,
                progressReported = true,
            ) == OutgoingCallFailureCallback.TERMINATED,
        )
    }

    @Test
    fun localHangupUsesTerminated() {
        require(
            OutgoingCallFailureCallbackPolicy.select(
                callStartFailed = false,
                progressReported = false,
            ) == OutgoingCallFailureCallback.TERMINATED,
        )
    }
}
