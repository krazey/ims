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
) {
    var onSmsReceived: ((Int, String, ByteArray) -> Unit)? = null
    var onSmsStatusReportReceived: ((Int, String, ByteArray) -> Unit)? = null

    private val smsLock = ReentrantLock()
    private var smsToken = 0
    private val smsHeadersMap = mutableMapOf<Int, smsHeaders>()

    fun clearState() {
        smsLock.withLock {
            smsHeadersMap.clear()
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
                try {
                    onSmsStatusReportReceived?.invoke(sms.ref.toInt(), "3gpp", ByteArray(2))
                } catch (t: Throwable) {
                    Rlog.d(tag, "Failed sending SMS ACK to framework", t)
                }
            }

            SmsType.RP_ERROR_FROM_NETWORK -> {
                Rlog.d(tag, "SMS error from network")
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
        val smsc = frameworkSmsc ?: identitySmsc ?: managerSmsc

        // RP-DATA destination address. Passing an empty string makes
        // PhoneNumberUtils.numberToCalledPartyBCD("") return null and crashes
        // SipSmsEncodeSms(), so keep it null when we genuinely do not know it.
        val rpSmsc = smsc?.let { "+$it" }
        val data = SipSmsEncodeSms(ref.toByte(), rpSmsc, pdu)
        Rlog.d(tag, "sending sms ${data.toHex()} to smsc $smsc rpSmsc=$rpSmsc")

        val smscSipIdentity = smscIdentity?.toString()?.let { normalizeSipTarget(it) }
        val requestUri = smscSipIdentity ?: "sip:$realm"
        val dest = smscSipIdentity ?: smsc?.let { "sip:+$it@$realm" } ?: "sip:$realm"

        val msg = SipRequest(
            SipMethod.MESSAGE,
            requestUri,
            commonHeadersProvider() + """
                From: <$mySip>
                To: <$dest>
                P-Preferred-Identity: <$mySip>
                P-Asserted-Identity: <$mySip>
                Expires: 600000
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

        responseCallbackSetter(msg.headers["call-id"]!![0]) { resp: SipResponse ->
            if (resp.statusCode == 200 || resp.statusCode == 202) {
                successCb()
            } else {
                failCb()
            }
            true
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
