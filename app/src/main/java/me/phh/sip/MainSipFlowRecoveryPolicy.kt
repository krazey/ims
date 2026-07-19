// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

internal data class MainSipFlowRecoveryState(
    val controlTransportIsTcp: Boolean,
    val imsReady: Boolean,
    val ipsecResourcesOpen: Boolean,
    val inboundServerListening: Boolean,
    val networkUsable: Boolean,
    val recoverableFlowLoss: Boolean,
    val reconnecting: Boolean,
    val accessMigrationPending: Boolean,
    val activeOrPendingCall: Boolean,
)

internal object MainSipFlowRecoveryPolicy {
    // Recurrent carrier-driven closures arrive at about five minutes. Keep
    // short-lived flow loss on the controlled full reconnect path.
    const val MIN_RECOVERABLE_FLOW_AGE_MS = 4 * 60_000L

    fun isRecoverableFlowAge(flowAgeMs: Long): Boolean =
        flowAgeMs >= MIN_RECOVERABLE_FLOW_AGE_MS

    fun mayKeepRegistration(state: MainSipFlowRecoveryState): Boolean {
        return state.controlTransportIsTcp &&
            state.imsReady &&
            state.ipsecResourcesOpen &&
            state.inboundServerListening &&
            state.networkUsable &&
            state.recoverableFlowLoss &&
            !state.reconnecting &&
            !state.accessMigrationPending &&
            !state.activeOrPendingCall
    }
}
