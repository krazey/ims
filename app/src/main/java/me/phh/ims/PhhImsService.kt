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
    private var receiverRegistered = false
    private val stateLock = Any()

    private val mmTelFeaturesBySlot = mutableMapOf<Int, PhhMmTelFeature>()
    private val configsBySlot = mutableMapOf<Int, PhhImsConfig>()
    private val imsRegistrationsBySlot = mutableMapOf<Int, ImsRegistrationImplBase>()

    override fun onCreate() {
        super.onCreate()
        Rlog.d(TAG, "onCreate")

        val intentFilter = IntentFilter()
        intentFilter.addAction(receiver.ALARM_PERIODIC_REGISTER)

        registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        armPeriodicRegisterAlarm()
    }

    private fun periodicRegisterPendingIntent(): PendingIntent {
        val intent = Intent(receiver.ALARM_PERIODIC_REGISTER)
        return PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun armPeriodicRegisterAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = periodicRegisterPendingIntent()

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
        val feature = synchronized(stateLock) { mmTelFeaturesBySlot[slotId] } ?: return

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

        val existingFeature = synchronized(stateLock) { mmTelFeaturesBySlot[slotId] }
        if (existingFeature != null) {
            notifyFeatureSubscriptionChanged(
                slotId,
                subscriptionId,
                "createMmTelFeatureForSubscription existing feature",
            )
            return existingFeature
        }

        return synchronized(stateLock) {
            mmTelFeaturesBySlot.getOrPut(slotId) {
                PhhMmTelFeature(slotId, subscriptionId)
            }
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

        return synchronized(stateLock) {
            configsBySlot.getOrPut(slotId) {
                PhhImsConfig()
            }
        }
    }

    override fun getRegistrationForSubscription(
        slotId: Int,
        subscriptionId: Int,
    ): ImsRegistrationImplBase {
        Rlog.d(TAG, "getRegistrationForSubscription slotId=$slotId subscriptionId=$subscriptionId")

        notifyFeatureSubscriptionChanged(slotId, subscriptionId, "getRegistrationForSubscription")

        return synchronized(stateLock) {
            imsRegistrationsBySlot.getOrPut(slotId) {
                ImsRegistrationImplBase()
            }
        }
    }

    fun getActiveSipHandlers() =
        synchronized(stateLock) {
            mmTelFeaturesBySlot.values.toList()
        }.mapNotNull { it.getSipHandlerOrNull() }

    inner class LocalBinder : Binder() {
        fun getService(): PhhImsService {
            Rlog.d(TAG, "LocalBinder getService")
            return this@PhhImsService
        }
    }

    override fun onDestroy() {
        Rlog.d(TAG, "onDestroy")

        val features = synchronized(stateLock) {
            val snapshot = mmTelFeaturesBySlot.values.toList()
            mmTelFeaturesBySlot.clear()
            configsBySlot.clear()
            imsRegistrationsBySlot.clear()
            snapshot
        }
        features.forEach { feature ->
            try {
                feature.onFeatureRemoved()
            } catch (t: Throwable) {
                Rlog.w(TAG, "Failed retiring MMTEL feature during service teardown", t)
            }
        }

        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(periodicRegisterPendingIntent())
        } catch (t: Throwable) {
            Rlog.w(TAG, "Failed cancelling periodic REGISTER alarm", t)
        }

        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver)
            } catch (t: Throwable) {
                Rlog.w(TAG, "Failed unregistering IMS broadcast receiver", t)
            }
            receiverRegistered = false
        }

        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun readyForFeatureCreation() {
        Rlog.d(TAG, "readyForFeatureCreation")

        if (instance != null && instance !== this) {
            throw RuntimeException()
        }

        instance = this
    }
}
