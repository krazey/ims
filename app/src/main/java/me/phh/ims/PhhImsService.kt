//SPDX-License-Identifier: GPL-2.0
package me.phh.ims

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.SystemClock
import android.telephony.Rlog
import android.telephony.ims.ImsService
import android.telephony.ims.feature.MmTelFeature
import android.telephony.ims.feature.RcsFeature
import android.telephony.ims.stub.ImsConfigImplBase
import android.telephony.ims.stub.ImsRegistrationImplBase

class PhhImsService : ImsService() {
    companion object {
        private const val TAG = "PHH ImsService"

        var instance: PhhImsService? = null
    }

    private val receiver = PhhImsBroadcastReceiver()

    private val mmTelFeaturesBySlot = mutableMapOf<Int, PhhMmTelFeature>()
    private val configsBySlot = mutableMapOf<Int, PhhImsConfig>()
    private val imsRegistrationsBySlot = mutableMapOf<Int, ImsRegistrationImplBase>()

    override fun onCreate() {
        Rlog.d(TAG, "onCreate")

        val intentFilter = IntentFilter()
        intentFilter.addAction(receiver.ALARM_PERIODIC_REGISTER)

        registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        armPeriodicRegisterAlarm()
    }

    fun armPeriodicRegisterAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(receiver.ALARM_PERIODIC_REGISTER)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        // We want recurring 3000s but recurring alarms don't wake up from
        // doze: alarm will re-arm itself.
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 3_000_000,
            pendingIntent,
        )

        Rlog.d(TAG, "Alarm set")
    }

    private fun notifyFeatureSubscriptionChanged(
        slotId: Int,
        subscriptionId: Int,
        reason: String,
    ) {
        val feature = mmTelFeaturesBySlot[slotId] ?: return

        Rlog.d(
            TAG,
            "notifyFeatureSubscriptionChanged slotId=$slotId " +
                "subscriptionId=$subscriptionId reason=$reason",
        )
        feature.onSubscriptionChangedFromService(subscriptionId)
    }

    override fun createMmTelFeatureForSubscription(
        slotId: Int,
        subscriptionId: Int,
    ): MmTelFeature {
        Rlog.d(
            TAG,
            "createMmTelFeatureForSubscription slotId=$slotId subscriptionId=$subscriptionId",
        )

        val existingFeature = mmTelFeaturesBySlot[slotId]
        if (existingFeature != null) {
            notifyFeatureSubscriptionChanged(
                slotId,
                subscriptionId,
                "createMmTelFeatureForSubscription existing feature",
            )
            return existingFeature
        }

        return PhhMmTelFeature(slotId, subscriptionId).also { feature ->
            mmTelFeaturesBySlot[slotId] = feature
        }
    }

    override fun createRcsFeatureForSubscription(
        slotId: Int,
        subscriptionId: Int,
    ): RcsFeature? {
        Rlog.d(TAG, "createRcsFeatureForSubscription slotId=$slotId subscriptionId=$subscriptionId")
        return null
    }

    override fun getConfigForSubscription(
        slotId: Int,
        subscriptionId: Int,
    ): ImsConfigImplBase {
        Rlog.d(TAG, "getConfigForSubscription slotId=$slotId subscriptionId=$subscriptionId")

        notifyFeatureSubscriptionChanged(slotId, subscriptionId, "getConfigForSubscription")

        return configsBySlot.getOrPut(slotId) {
            PhhImsConfig()
        }
    }

    override fun getRegistrationForSubscription(
        slotId: Int,
        subscriptionId: Int,
    ): ImsRegistrationImplBase {
        Rlog.d(TAG, "getRegistrationForSubscription slotId=$slotId subscriptionId=$subscriptionId")

        notifyFeatureSubscriptionChanged(slotId, subscriptionId, "getRegistrationForSubscription")

        return imsRegistrationsBySlot.getOrPut(slotId) {
            ImsRegistrationImplBase()
        }
    }

    fun getActiveSipHandlers() =
        mmTelFeaturesBySlot.values.mapNotNull { it.getSipHandlerOrNull() }

    class LocalBinder : Binder() {
        fun getService(): PhhImsService {
            Rlog.d(TAG, "LocalBinder getService")
            return PhhImsService()
        }
    }

    override fun onDestroy() {
        Rlog.d(TAG, "onDestroy")
        instance = null
    }

    override fun readyForFeatureCreation() {
        Rlog.d(TAG, "readyForFeatureCreation")

        if (instance != null && instance !== this) {
            throw RuntimeException()
        }

        instance = this
    }
}
