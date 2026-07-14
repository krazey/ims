// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.Rlog

private const val SMSC_TAG = "PHH SipHandler"

internal data class smsHeaders(
    val dest: String,
    val callId: String,
    val cseq: String,
)

internal fun decodeSmscScaPdu(raw: String?): String? {
    val hex = raw
        ?.trim()
        ?.trim('"')
        ?.replace(Regex("\\s+"), "")
        ?: return null

    if (!hex.matches(Regex("(?i)[0-9a-f]+")) || hex.length < 4 || hex.length % 2 != 0) {
        return null
    }

    return try {
        val scaLen = hex.substring(0, 2).toInt(16)
        val expectedLen = (1 + scaLen) * 2
        if (scaLen < 2 || hex.length != expectedLen) return null

        val addrHex = hex.substring(4, expectedLen)
        val digits = addrHex.chunked(2).joinToString("") { octet ->
            "${octet[1]}${octet[0]}"
        }.trimEnd('F', 'f')

        if (digits.length < 5) return null

        // 0x91 = international ISDN/telephone number. For RP-DATA we pass the
        // canonical digits and add '+' later when building the RP SMSC address.
        digits.takeIf { it.all(Char::isDigit) }
    } catch (t: Throwable) {
        null
    }
}

internal fun normalizeSmscNumber(raw: String?): String? {
    val trimmed = raw
        ?.trim()
        ?.trim('"')
        ?.takeIf { it.isNotBlank() && it != "null" }
        ?: return null

    decodeSmscScaPdu(trimmed)?.let { decoded ->
        Rlog.d(SMSC_TAG, "Decoded SMSC SCA-PDU digits=${decoded.length}")
        return decoded
    }

    val strictNumber = Regex("""^\+?([0-9]{5,20})$""").matchEntire(trimmed)
    if (strictNumber != null) return strictNumber.groupValues[1]

    // RIL GET_SMSC_ADDRESS can be returned as: "491760000443",145
    val looseNumber = Regex("""\+?([0-9]{5,20})""").find(trimmed)
    if (looseNumber != null) return looseNumber.groupValues[1]

    return null
}
