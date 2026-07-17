//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

internal enum class RegisterRefreshResponseAction {
    APPLY_SUCCESS,
    KEEP_REGISTRATION,
}

internal object SipRegisterRefreshResponsePolicy {
    fun action(statusCode: Int): RegisterRefreshResponseAction =
        if (statusCode == 200) {
            RegisterRefreshResponseAction.APPLY_SUCCESS
        } else {
            RegisterRefreshResponseAction.KEEP_REGISTRATION
        }
}
