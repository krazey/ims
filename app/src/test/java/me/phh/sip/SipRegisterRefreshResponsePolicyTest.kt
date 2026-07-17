//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Assert.assertEquals
import org.junit.Test

class SipRegisterRefreshResponsePolicyTest {
    @Test
    fun `successful refresh updates registration`() {
        assertEquals(
            RegisterRefreshResponseAction.APPLY_SUCCESS,
            SipRegisterRefreshResponsePolicy.action(200),
        )
    }

    @Test
    fun `failed refresh preserves established registration`() {
        listOf(202, 401, 494, 500, 503).forEach { statusCode ->
            assertEquals(
                RegisterRefreshResponseAction.KEEP_REGISTRATION,
                SipRegisterRefreshResponsePolicy.action(statusCode),
            )
        }
    }
}
