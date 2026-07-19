//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

internal enum class RegisterRefreshResponseAction {
    APPLY_SUCCESS,
    KEEP_REGISTRATION,
    RECONNECT,
}

internal object SipRegisterRefreshResponsePolicy {
    const val DEFAULT_RETRY_DELAY_MS = 5_000L
    private const val MIN_RETRY_DELAY_MS = 1_000L
    private const val MAX_RETRY_DELAY_MS = 8_000L

    fun action(
        statusCode: Int,
        sentOnRecoveredFlow: Boolean,
    ): RegisterRefreshResponseAction = when {
        statusCode == 200 -> RegisterRefreshResponseAction.APPLY_SUCCESS
        sentOnRecoveredFlow -> RegisterRefreshResponseAction.RECONNECT
        else -> RegisterRefreshResponseAction.KEEP_REGISTRATION
    }

    fun retryDelayMs(response: SipResponse): Long {
        return (SipRegistrationFailurePolicy.retryAfterMs(response)
            ?: DEFAULT_RETRY_DELAY_MS)
            .coerceIn(MIN_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS)
    }
}
