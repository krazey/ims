//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import android.telephony.Rlog

class WfcSubscriptionSettingMonitor(
    private val tag: String,
    private val ctxt: Context,
    handler: Handler,
    private val subId: Int,
    private val onWfcDisabled: (String) -> Unit,
) {
    private data class WfcState(
        val enabled: Boolean?,
        val mode: Int?,
    )

    private val simInfoUri = Uri.parse("content://telephony/siminfo")
    @Volatile private var observedWfcEnabled: Boolean? = null
    @Volatile private var observedWfcMode: Int? = null
    @Volatile private var lastObservedChangeUptimeMs: Long = SystemClock.uptimeMillis()

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handleChanged("siminfo observer")
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            handleChanged("siminfo observer uri=$uri")
        }
    }

    fun start() {
        val state = readState()
        observedWfcEnabled = state.enabled
        observedWfcMode = state.mode
        lastObservedChangeUptimeMs = SystemClock.uptimeMillis()
        Rlog.d(tag, "Initial WFC subscription setting enabled=${state.enabled} mode=${state.mode}")
        try {
            ctxt.contentResolver.registerContentObserver(simInfoUri, true, observer)
            Rlog.d(tag, "Registered WFC subscription setting observer")
        } catch (t: Throwable) {
            Rlog.d(tag, "Registering WFC subscription setting observer failed", t)
        }
    }

    fun stop() {
        try {
            ctxt.contentResolver.unregisterContentObserver(observer)
            Rlog.d(tag, "Unregistered WFC subscription setting observer for subId=$subId")
        } catch (t: Throwable) {
            Rlog.d(tag, "Unregistering WFC subscription setting observer failed", t)
        }
    }

    fun isWifiPreferredOrOnly(): Boolean {
        val mode = observedWfcMode ?: return false
        return observedWfcEnabled == true && mode != WIFI_MODE_CELLULAR_PREFERRED
    }

    fun lastChangeUptimeMs(): Long = lastObservedChangeUptimeMs

    private fun readState(): WfcState {
        return try {
            ctxt.contentResolver.query(
                simInfoUri,
                arrayOf("_id", "wfc_ims_enabled", "wfc_ims_mode"),
                null,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndex("_id")
                val wfcColumn = cursor.getColumnIndex("wfc_ims_enabled")
                val modeColumn = cursor.getColumnIndex("wfc_ims_mode")
                if (idColumn < 0 || wfcColumn < 0) {
                    Rlog.w(tag, "siminfo is missing WFC columns id=$idColumn wfc=$wfcColumn mode=$modeColumn")
                    return WfcState(enabled = null, mode = null)
                }
                while (cursor.moveToNext()) {
                    if (cursor.getInt(idColumn) == subId) {
                        val enabled = cursor.getInt(wfcColumn) == 1
                        val mode = if (modeColumn >= 0) cursor.getInt(modeColumn) else null
                        return WfcState(enabled = enabled, mode = mode)
                    }
                }
                Rlog.w(tag, "No siminfo row found for subId=$subId while checking WFC")
                WfcState(enabled = null, mode = null)
            } ?: WfcState(enabled = null, mode = null)
        } catch (t: Throwable) {
            Rlog.d(tag, "Reading WFC subscription property failed", t)
            WfcState(enabled = null, mode = null)
        }
    }

    private fun handleChanged(reason: String) {
        val state = readState()
        val enabled = state.enabled ?: return
        val oldEnabled = observedWfcEnabled
        val oldMode = observedWfcMode
        observedWfcEnabled = enabled
        observedWfcMode = state.mode

        if (oldEnabled == enabled && oldMode == state.mode) return

        lastObservedChangeUptimeMs = SystemClock.uptimeMillis()
        Rlog.d(
            tag,
            "WFC subscription setting changed oldEnabled=$oldEnabled enabled=$enabled " +
                "oldMode=$oldMode mode=${state.mode} reason=$reason",
        )

        if (oldEnabled == true && !enabled) {
            onWfcDisabled(reason)
        }
    }

    private companion object {
        // ImsMmTelManager.WIFI_MODE_CELLULAR_PREFERRED
        const val WIFI_MODE_CELLULAR_PREFERRED = 1
    }
}
