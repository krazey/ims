// SPDX-License-Identifier: GPL-2.0
package me.phh.ims

internal enum class OutgoingCallFailureCallback {
    INITIATING_FAILED,
    TERMINATED,
}

internal object OutgoingCallFailureCallbackPolicy {
    fun select(
        callStartFailed: Boolean,
        progressReported: Boolean,
    ): OutgoingCallFailureCallback {
        return if (callStartFailed && !progressReported) {
            OutgoingCallFailureCallback.INITIATING_FAILED
        } else {
            OutgoingCallFailureCallback.TERMINATED
        }
    }
}
