//SPDX-License-Identifier: GPL-2.0
package me.phh.ims

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.Rlog
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PhhImsBroadcastReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PHH ImsBroadcastReceiver"
    }

    val ALARM_PERIODIC_REGISTER = "me.phh.ims.ALARM_PERIODIC_REGISTER"

    override fun onReceive(ctxt: Context, intent: Intent) {
        Rlog.d(TAG, "Alarm fired with ${intent.action}")

        if (intent.action != ALARM_PERIODIC_REGISTER) {
            return
        }

        val imsService = PhhImsService.Companion.instance
        if (imsService == null) {
            Rlog.w(TAG, "Periodic REGISTER alarm fired without active ImsService")
            return
        }

        // Re-arm alarm.
        imsService.armPeriodicRegisterAlarm()

        CoroutineScope(Dispatchers.IO).launch {
            val sipHandlers = imsService.getActiveSipHandlers()

            if (sipHandlers.isEmpty()) {
                Rlog.d(TAG, "Periodic REGISTER skipped: no active SipHandler")
                return@launch
            }

            sipHandlers.forEach { sipHandler ->
                try {
                    sipHandler.register()
                } catch (e: IOException) {
                    Rlog.w(TAG, "Periodic REGISTER failed; requesting controlled reconnect", e)
                    sipHandler.recoverAfterPeriodicRegisterFailure(e)
                }
            }
        }
    }
}
