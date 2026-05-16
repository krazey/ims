//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

data class SipCarrierSettings(
    val mcc: String,
    val mnc: String,
    val isControlSocketUdp: Boolean,
    val requireNonsessAka: Boolean,
) {
    companion object {
        fun fromSimOperator(simOperator: String): SipCarrierSettings {
            val mcc = simOperator.substring(0 until 3)
            val mnc = simOperator.substring(3).let { if (it.length == 2) "0$it" else it }
            val mccMnc = mcc + mnc

            return SipCarrierSettings(
                mcc = mcc,
                mnc = mnc,
                isControlSocketUdp = when (mccMnc) {
                    "450006" -> true // LG U+ can only do UDP
                    "208010" -> true // 20810 can do TCP and UDP. use this for testing
                    else -> false
                },
                // Sess is more secure so default to it.
                requireNonsessAka = when (mccMnc) {
                    "450006" -> true
                    else -> false
                },
            )
        }
    }
}
