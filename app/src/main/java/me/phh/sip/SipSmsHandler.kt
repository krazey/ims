// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.content.Context
import android.net.Uri
import android.telephony.Rlog
import android.telephony.SmsManager
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class SipSmsHandler(
    private val tag: String,
    private val ctxt: Context,
    private val subId: Int,
    private val realmProvider: () -> String,
    private val commonHeadersProvider: () -> SipHeadersMap,
    private val mySipProvider: () -> String,
    private val writerProvider: () -> OutputStream,
    private val responseCallbackSetter: (String, (SipResponse) -> Boolean) -> Unit,
    private val smsSipFailureListener: (String, Int) -> Unit = { _, _ -> },
    private val timeoutScheduler: (Long, () -> Unit) -> Unit,
) {
    var onSmsReceived: ((Int, String, ByteArray) -> Unit)? = null
    var onSmsStatusReportReceived: ((Int, String, ByteArray) -> Unit)? = null

    private val smsLock = ReentrantLock()
    private var smsToken = 0
    private val smsHeadersMap = mutableMapOf<Int, smsHeaders>()

    private data class PendingOutgoingSms(
        val callId: String,
        val ref: Int,
        val successCb: () -> Unit,
        val failCb: () -> Unit,
    )

    private val pendingOutgoingSmsByCallId = mutableMapOf<String, PendingOutgoingSms>()
    private val pendingOutgoingSmsByRef = mutableMapOf<Int, PendingOutgoingSms>()

    private fun smsRefToInt(ref: Byte): Int = ref.toInt() and 0xff

    private fun rememberPendingOutgoingSms(
        callId: String,
        ref: Int,
        successCb: () -> Unit,
        failCb: () -> Unit,
    ) {
        val pending = PendingOutgoingSms(
            callId = callId,
            ref = ref and 0xff,
            successCb = successCb,
            failCb = failCb,
        )

        smsLock.withLock {
            pendingOutgoingSmsByCallId[callId] = pending
            pendingOutgoingSmsByRef[pending.ref] = pending
        }

        timeoutScheduler(15_000L) {
            val expired = smsLock.withLock {
                pendingOutgoingSmsByCallId.remove(callId)?.also {
                    pendingOutgoingSmsByRef.remove(it.ref)
                }
            }

            if (expired != null) {
                Rlog.w(
                    tag,
                    "Timed out waiting for RP result for outgoing SMS " +
                        "ref=${expired.ref} callId=${expired.callId}",
                )

                try {
                    expired.failCb()
                } catch (t: Throwable) {
                    Rlog.d(tag, "Failed reporting outgoing SMS RP timeout", t)
                }
            }
        }
    }

    private fun removePendingOutgoingSms(request: SipRequest, sms: SipSms): PendingOutgoingSms? {
        val inReplyTo = request.headers["in-reply-to"]?.getOrNull(0)
        val ref = smsRefToInt(sms.ref)

        return smsLock.withLock {
            val pending = inReplyTo?.let { pendingOutgoingSmsByCallId.remove(it) }
                ?: pendingOutgoingSmsByRef.remove(ref)

            if (pending != null) {
                pendingOutgoingSmsByCallId.remove(pending.callId)
                pendingOutgoingSmsByRef.remove(pending.ref)
            }

            pending
        }
    }

    private fun completePendingOutgoingSms(
        request: SipRequest,
        sms: SipSms,
        success: Boolean,
    ): Boolean {
        val pending = removePendingOutgoingSms(request, sms) ?: return false

        Rlog.d(
            tag,
            "Completing outgoing SMS from RP result: " +
                "success=$success ref=${pending.ref} callId=${pending.callId}",
        )

        try {
            if (success) {
                pending.successCb()
            } else {
                pending.failCb()
            }
        } catch (t: Throwable) {
            Rlog.d(tag, "Failed reporting outgoing SMS RP result", t)
        }

        return true
    }

    private fun failPendingOutgoingSmsForSipResponse(callId: String, response: SipResponse) {
        val pending = smsLock.withLock {
            pendingOutgoingSmsByCallId.remove(callId)?.also {
                pendingOutgoingSmsByRef.remove(it.ref)
            }
        } ?: return

        Rlog.w(
            tag,
            "Outgoing SMS SIP transaction failed before RP result: " +
                "status=${response.statusCode} ref=${pending.ref} callId=$callId",
        )

        try {
            smsSipFailureListener(realmProvider(), response.statusCode)
            pending.failCb()
        } catch (t: Throwable) {
            Rlog.d(tag, "Failed reporting outgoing SMS SIP failure", t)
        }
    }

    fun clearState() {
        smsLock.withLock {
            smsHeadersMap.clear()
            pendingOutgoingSmsByCallId.clear()
            pendingOutgoingSmsByRef.clear()
        }
    }

    fun handleSms(request: SipRequest): Int {
        val sms = request.body.SipSmsDecode()
        if (sms == null) {
            Rlog.w(tag, "Could not decode sms pdu")
            return 500
        }

        Rlog.d(tag, "Decoded SMS type ${sms.type}, ${sms.pdu?.toString()}")
        when (sms.type) {
            SmsType.RP_DATA_FROM_NETWORK -> {
                val receivedCb = onSmsReceived
                if (receivedCb == null) {
                    Rlog.d(tag, "No onSmsReceived callback!")
                    return 500
                }

                val token = smsLock.withLock { smsToken++ }
                val dest = request.headers["from"]!![0]
                    .getParams()
                    .component1()
                    .trimStart('<')
                    .trimEnd('>')
                val callId = request.headers["call-id"]!![0]
                val cseq = request.headers["cseq"]!![0]
                smsHeadersMap[token] = smsHeaders(dest, callId, cseq)

                try {
                    receivedCb(token, "3gpp", sms.pdu!!)
                } catch (t: Throwable) {
                    Rlog.d(tag, "Failed sending SMS to framework", t)
                }
            }

            SmsType.RP_ACK_FROM_NETWORK -> {
                if (!completePendingOutgoingSms(request, sms, success = true)) {
                    try {
                        onSmsStatusReportReceived?.invoke(smsRefToInt(sms.ref), "3gpp", ByteArray(2))
                    } catch (t: Throwable) {
                        Rlog.d(tag, "Failed sending SMS ACK to framework", t)
                    }
                }
            }

            SmsType.RP_ERROR_FROM_NETWORK -> {
                if (!completePendingOutgoingSms(request, sms, success = false)) {
                    Rlog.d(tag, "SMS error from network without matching pending outgoing SMS")
                }
            }

            else -> return 500
        }

        return 200
    }

    fun sendSms(
        smsSmsc: String?,
        pdu: ByteArray,
        ref: Int,
        successCb: (() -> Unit),
        failCb: (() -> Unit),
    ) {
        val smsManager = ctxt.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
        val smscIdentity = try {
            val i = smsManager
                .javaClass
                .getMethod("getSmscIdentity")
                .invoke(smsManager) as? Uri
            if (i?.host.isNullOrBlank()) null else i
        } catch (t: Throwable) {
            null
        }
        Rlog.d(tag, "Got smscIdentity $smscIdentity")

        val frameworkSmsc = normalizeSmscNumber(smsSmsc)
        val identitySmsc = normalizeSmscNumber(smscIdentity?.host)
        val managerSmsc = try {
            val smscStr = smsManager.smscAddress
            val parsed = normalizeSmscNumber(smscStr)
            Rlog.d(tag, "Got smsc $smscStr, parsed $parsed")
            parsed
        } catch (t: Throwable) {
            Rlog.d(tag, "smscAddress failed", t)
            null
        }

        val realm = realmProvider()
        val mySip = mySipProvider()
        val rawSmsc = frameworkSmsc ?: identitySmsc ?: managerSmsc

        /*
         * SingTel IMS SMS must be sent to the operator SMSC URI in the
         * ims.singtel.com realm. Decode framework-provided PDU SMSC addresses
         * before building both RP-DATA and the SIP MESSAGE target.
         */
        val isSingTelSms = realm.equals("ims.mnc001.mcc525.3gppnetwork.org", ignoreCase = true) ||
            realm.equals("ims.singtel.com", ignoreCase = true)

        fun decodeSingTelSmscPduAddress(value: String?): String? {
            val raw = value
                ?.trim()
                ?.removePrefix("<")
                ?.removeSuffix(">")
                ?.removePrefix("sip:")
                ?.substringBefore("@")
                ?.substringBefore(";")
                ?.trim()
                ?.trimStart('+')
                ?: return null

            val hex = raw.filter { ch -> ch.isDigit() || ch.lowercaseChar() in 'a'..'f' }
            if (hex.length < 4 || hex.length % 2 != 0) return null

            val len = hex.substring(0, 2).toIntOrNull(16) ?: return null
            if (len <= 1) return null

            val bcdStart = 4
            val bcdEnd = bcdStart + (len - 1) * 2
            if (hex.length < bcdEnd) return null

            val bcd = hex.substring(bcdStart, bcdEnd)
            val digits = buildString {
                var i = 0
                while (i + 1 < bcd.length) {
                    val lo = bcd[i]
                    val hi = bcd[i + 1]
                    if (hi != 'f' && hi != 'F') append(hi)
                    if (lo != 'f' && lo != 'F') append(lo)
                    i += 2
                }
            }

            if (digits.isBlank()) return null
            return when {
                digits.startsWith("65") -> "+$digits"
                digits.length == 8 -> "+65$digits"
                else -> "+$digits"
            }
        }

        fun normalizeSingTelSmscAddress(value: String?): String? {
            decodeSingTelSmscPduAddress(value)?.let { return it }

            val userPart = value
                ?.trim()
                ?.removePrefix("<")
                ?.removeSuffix(">")
                ?.removePrefix("sip:")
                ?.substringBefore("@")
                ?.substringBefore(";")
                ?.trim()
                ?: return null

            if (userPart.isBlank()) return null
            val digits = userPart.trimStart('+')
            return when {
                userPart.startsWith("+") -> userPart
                digits.startsWith("65") -> "+$digits"
                digits.length == 8 -> "+65$digits"
                else -> "+$digits"
            }
        }

        val smsc = if (isSingTelSms) {
            /*
             * Use the stock SingTel IMS SMSC. Other discovered/framework SMSC
             * values can be accepted by SIP routing but rejected at RP level.
             */
            "+6596197777"
        } else {
            rawSmsc
        }

        // RP-DATA destination address. Passing an empty string makes
        // PhoneNumberUtils.numberToCalledPartyBCD("") return null and crashes
        // SipSmsEncodeSms(), so keep it null when we genuinely do not know it.
        val rpSmsc = smsc?.let { if (it.startsWith("+")) it else "+$it" }
        val data = SipSmsEncodeSms(ref.toByte(), rpSmsc, pdu)
        Rlog.d(tag, "sending sms ${data.toHex()} to rawSmsc=$rawSmsc smsc=$smsc rpSmsc=$rpSmsc")

        val smscSipIdentity = smscIdentity?.toString()?.let { normalizeSipTarget(it) }
        val singtelSmscUri = smsc?.let { "sip:${if (it.startsWith("+")) it else "+$it"}@ims.singtel.com" }
        val requestUri = if (isSingTelSms) {
            singtelSmscUri ?: "sip:ims.singtel.com"
        } else {
            smscSipIdentity ?: "sip:$realm"
        }
        val dest = if (isSingTelSms) {
            requestUri
        } else {
            smscSipIdentity ?: smsc?.let { "sip:+$it@$realm" } ?: "sip:$realm"
        }
        if (isSingTelSms) {
            Rlog.d(tag, "Using SingTel IMS SMS target requestUri=$requestUri dest=$dest rawSmsc=$rawSmsc smsc=$smsc rpSmsc=$rpSmsc")
        }

        val msg = SipRequest(
            SipMethod.MESSAGE,
            requestUri,
            commonHeadersProvider() + """
                From: <$mySip>
                To: <$dest>
                P-Preferred-Identity: <$mySip>
                P-Asserted-Identity: <$mySip>
                Expires: 7200
                Content-Type: application/vnd.3gpp.sms
                Supported: sec-agree, path
                Require: sec-agree
                Proxy-Require: sec-agree
                Allow: MESSAGE
                Accept-Contact: *;+g.3gpp.smsip;require;explicit
                Request-Disposition: no-fork
            """.toSipHeadersMap(),
            data,
        )

        val callId = msg.headers["call-id"]!![0]
        rememberPendingOutgoingSms(callId, ref, successCb, failCb)

        responseCallbackSetter(callId) { resp: SipResponse ->
            when {
                resp.statusCode == 200 || resp.statusCode == 202 -> {
                    Rlog.d(
                        tag,
                        "Outgoing SMS SIP MESSAGE accepted; waiting for RP result " +
                            "ref=${ref and 0xff} callId=$callId status=${resp.statusCode}",
                    )
                    true
                }

                resp.statusCode < 200 -> false

                else -> {
                    failPendingOutgoingSmsForSipResponse(callId, resp)
                    true
                }
            }
        }

        Rlog.d(tag, "Sending $msg")
        val writer = writerProvider()
        synchronized(writer) {
            writer.write(msg.toByteArray())
        }
    }

    fun sendSmsAck(token: Int, ref: Int, error: Boolean) {
        Rlog.d(tag, "sending sms ack")
        val body = SipSmsEncodeAck(ref.toByte())
        val headers = smsHeadersMap.remove(token) ?: return

        // Do not send ACK on framework error. Should we send an error report?
        if (error) {
            return
        }

        val msg = SipRequest(
            SipMethod.MESSAGE,
            headers.dest,
            commonHeadersProvider() + """
                Cseq: ${headers.cseq}
                In-Reply-To: ${headers.callId}
                Content-Type: application/vnd.3gpp.sms
                Proxy-Require: sec-agree
                Require: sec-agree
                Allow: MESSAGE
                Supported: path, gruu, sec-agree
                Request-Disposition: no-fork
                Accept-Contact: *;+g.3gpp.smsip
            """.toSipHeadersMap(),
            body,
        )

        // Ignore response.
        responseCallbackSetter(msg.headers["call-id"]!![0]) { true }

        Rlog.d(tag, "Sending $msg")
        val writer = writerProvider()
        synchronized(writer) {
            writer.write(msg.toByteArray())
        }
    }

    private fun normalizeSipTarget(raw: String): String =
        if (raw.startsWith("sip:", ignoreCase = true) || raw.startsWith("tel:", ignoreCase = true)) {
            raw
        } else {
            "sip:$raw"
        }
}
