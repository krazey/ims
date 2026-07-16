// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Test

class SipRegistrationFailurePolicyTest {
    private fun response(code: Int, retryAfter: String? = null): SipResponse = SipResponse(
        code,
        "Failure",
        retryAfter?.let { mapOf("retry-after" to listOf(it)) }.orEmpty(),
    )

    @Test
    fun `Retry-After accepts delay and ignores parameters`() {
        require(SipRegistrationFailurePolicy.retryAfterMs(response(503, "37;duration=9")) == 37_000L)
        require(SipRegistrationFailurePolicy.retryAfterMs(response(503, "invalid")) == null)
    }

    @Test
    fun `403 permanent stop does not retry or rotate`() {
        val decision = SipRegistrationFailurePolicy.decide(
            response(403),
            SipRegistrationRecoveryPolicy(
                forbiddenPcscfPolicy = RegistrationForbiddenPcscfPolicy.PERMANENT_STOP,
            ),
        )

        require(!decision.retry)
        require(!decision.blockCurrentPcscf)
    }

    @Test
    fun `403 same and next P-CSCF policies differ only in rotation`() {
        val same = SipRegistrationFailurePolicy.decide(
            response(403, "60"),
            SipRegistrationRecoveryPolicy(
                forbiddenPcscfPolicy = RegistrationForbiddenPcscfPolicy.SAME_PCSCF,
            ),
        )
        val next = SipRegistrationFailurePolicy.decide(
            response(403, "60"),
            SipRegistrationRecoveryPolicy(
                forbiddenPcscfPolicy = RegistrationForbiddenPcscfPolicy.NEXT_PCSCF,
            ),
        )

        require(same.retry && !same.blockCurrentPcscf)
        require(next.retry && next.blockCurrentPcscf)
        require(same.retryAfterMs == 60_000L && next.retryAfterMs == 60_000L)
    }

    @Test
    fun `short 503 Retry-After stays on current P-CSCF`() {
        val shortRetry = SipRegistrationFailurePolicy.decide(
            response(503, "15"),
            SipRegistrationRecoveryPolicy(),
        )
        val longRetry = SipRegistrationFailurePolicy.decide(
            response(503, "60"),
            SipRegistrationRecoveryPolicy(),
        )

        require(shortRetry.retry && !shortRetry.blockCurrentPcscf)
        require(longRetry.retry && longRetry.blockCurrentPcscf)
    }
}
