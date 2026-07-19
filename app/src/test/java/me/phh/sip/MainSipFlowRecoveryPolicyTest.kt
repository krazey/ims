// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainSipFlowRecoveryPolicyTest {
    private val eligibleState = MainSipFlowRecoveryState(
        controlTransportIsTcp = true,
        imsReady = true,
        ipsecResourcesOpen = true,
        inboundServerListening = true,
        networkUsable = true,
        recoverableFlowLoss = true,
        reconnecting = false,
        accessMigrationPending = false,
        activeOrPendingCall = false,
    )

    @Test
    fun keepsRegistrationForRecoverableTcpFlowLoss() {
        assertTrue(MainSipFlowRecoveryPolicy.mayKeepRegistration(eligibleState))
    }

    @Test
    fun recognizesOnlyLongLivedFlowClosures() {
        assertFalse(
            MainSipFlowRecoveryPolicy.isRecoverableFlowAge(
                MainSipFlowRecoveryPolicy.MIN_RECOVERABLE_FLOW_AGE_MS - 1L,
            ),
        )
        assertTrue(
            MainSipFlowRecoveryPolicy.isRecoverableFlowAge(
                MainSipFlowRecoveryPolicy.MIN_RECOVERABLE_FLOW_AGE_MS,
            ),
        )
    }

    @Test
    fun rejectsLossesThatCanInvalidateRegistrationOrCalls() {
        val ineligibleStates = listOf(
            eligibleState.copy(controlTransportIsTcp = false),
            eligibleState.copy(imsReady = false),
            eligibleState.copy(ipsecResourcesOpen = false),
            eligibleState.copy(inboundServerListening = false),
            eligibleState.copy(networkUsable = false),
            eligibleState.copy(recoverableFlowLoss = false),
            eligibleState.copy(reconnecting = true),
            eligibleState.copy(accessMigrationPending = true),
            eligibleState.copy(activeOrPendingCall = true),
        )

        ineligibleStates.forEach { state ->
            assertFalse(state.toString(), MainSipFlowRecoveryPolicy.mayKeepRegistration(state))
        }
    }
}
