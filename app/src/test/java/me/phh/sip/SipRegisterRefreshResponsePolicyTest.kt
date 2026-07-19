//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Assert.assertEquals
import org.junit.Test

class SipRegisterRefreshResponsePolicyTest {
    private fun response(retryAfter: String? = null): SipResponse = SipResponse(
        503,
        "Service Unavailable",
        retryAfter?.let { mapOf("retry-after" to listOf(it)) }.orEmpty(),
    )

    @Test
    fun `successful refresh updates registration`() {
        assertEquals(
            RegisterRefreshResponseAction.APPLY_SUCCESS,
            SipRegisterRefreshResponsePolicy.action(
                statusCode = 200,
                sentOnRecoveredFlow = false,
            ),
        )
    }

    @Test
    fun `failed refresh preserves established registration`() {
        listOf(202, 401, 494, 500, 503).forEach { statusCode ->
            assertEquals(
                RegisterRefreshResponseAction.KEEP_REGISTRATION,
                SipRegisterRefreshResponsePolicy.action(
                    statusCode = statusCode,
                    sentOnRecoveredFlow = false,
                ),
            )
        }
    }

    @Test
    fun `failed refresh on recovered flow requires full registration`() {
        listOf(401, 500, 503).forEach { statusCode ->
            assertEquals(
                RegisterRefreshResponseAction.RECONNECT,
                SipRegisterRefreshResponsePolicy.action(
                    statusCode = statusCode,
                    sentOnRecoveredFlow = true,
                ),
            )
        }
    }

    @Test
    fun `successful refresh accepts recovered flow`() {
        assertEquals(
            RegisterRefreshResponseAction.APPLY_SUCCESS,
            SipRegisterRefreshResponsePolicy.action(
                statusCode = 200,
                sentOnRecoveredFlow = true,
            ),
        )
    }

    @Test
    fun `failed refresh retries promptly while alarm wake is active`() {
        assertEquals(
            5_000L,
            SipRegisterRefreshResponsePolicy.retryDelayMs(response()),
        )
        assertEquals(
            1_000L,
            SipRegisterRefreshResponsePolicy.retryDelayMs(response("0")),
        )
        assertEquals(
            8_000L,
            SipRegisterRefreshResponsePolicy.retryDelayMs(response("60")),
        )
    }
}
