//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.Rlog
import android.telephony.TelephonyManager

object RegistrationCellInfoLogger {
    fun log(logTag: String, telephonyManager: TelephonyManager) {
        for (cell in telephonyManager.getAllCellInfo()) {
            if (cell is CellInfoLte) {
                val cellSignalStrength = cell.cellSignalStrength
                Rlog.d(logTag, "LTE registration signalDbm=${cellSignalStrength.dbm}")
            } else if (cell is CellInfoNr) {
                val cellSignalStrength = cell.cellSignalStrength
                Rlog.d(logTag, "NR registration signalDbm=${cellSignalStrength.dbm}")
            } else if (cell is CellInfoWcdma) {
                val cellSignalStrength = cell.cellSignalStrength
                Rlog.d(logTag, "WCDMA registration signalDbm=${cellSignalStrength.dbm}")
            } else if (cell is CellInfoGsm) {
                val cellSignalStrength = cell.cellSignalStrength
                Rlog.d(logTag, "GSM registration signalDbm=${cellSignalStrength.dbm}")
            }
        }
    }
}
