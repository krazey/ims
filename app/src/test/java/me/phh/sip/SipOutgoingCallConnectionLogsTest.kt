// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Test

class SipOutgoingCallConnectionLogsTest {
    @Test
    fun `duplicate connection log describes blank call id without recursion`() {
        require(
            SipOutgoingCallConnectionLogs.duplicateConnectedNotifyLog("", "RTP") ==
                "Ignoring duplicate outgoing connected notification: " +
                "callId=<blank> reason=RTP",
        )
    }
}
