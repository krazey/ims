// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.Rlog
import java.io.OutputStream

internal object SipMessageWriter {
    fun write(
        tag: String,
        writer: OutputStream,
        bytes: ByteArray,
        label: String,
    ): Boolean {
        return try {
            synchronized(writer) {
                writer.write(bytes)
                writer.flush()
            }
            true
        } catch (t: Throwable) {
            Rlog.w(tag, "Failed to write SIP bytes for $label", t)
            false
        }
    }
}
