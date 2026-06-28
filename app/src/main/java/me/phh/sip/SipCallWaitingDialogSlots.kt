// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

internal object SipCallWaitingDialogSlots {
    fun callForIncomingInviteDialog(
        callId: String,
        currentCall: SipHandler.Call?,
        pendingSwapHeldActiveCall: SipHandler.Call?,
        heldForegroundCall: SipHandler.Call?,
        logDebug: (String) -> Unit,
    ): SipHandler.Call? {
        if (currentCall != null && currentCall.callIdOrEmpty() == callId) return currentCall

        if (pendingSwapHeldActiveCall != null &&
            pendingSwapHeldActiveCall.callIdOrEmpty() == callId
        ) {
            logDebug("Routing incoming INVITE to pending swap held dialog: callId=$callId")
            return pendingSwapHeldActiveCall
        }

        if (heldForegroundCall != null && heldForegroundCall.callIdOrEmpty() == callId) {
            logDebug("Routing incoming INVITE to held foreground dialog: callId=$callId")
            return heldForegroundCall
        }

        return null
    }
}
