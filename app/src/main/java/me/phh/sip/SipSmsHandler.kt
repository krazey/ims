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
    private val carrierSettings: SipCarrierSettings,
    private val realmProvider: () -> String,
    private val commonHeadersProvider: () -> SipHeadersMap,
    private val mySipProvider: () -> String,
    private val writerProvider: () -> OutputStream,
    private val responseCallbackSetter: (String, (SipResponse) -> Boolean) -> Unit,
    private val responseCallbackRemover: (String) -> Unit,
    private val smsSipFailureListener: (String, Int) -> Unit = { _, _ -> },
    private val sipWriteFailureListener: (String) -> Unit,
    private val timeoutScheduler: (Long, () -> Unit) -> Unit,
    private val registrationTechProvider: () -> Int,
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

        timeoutScheduler(carrierSettings.smsPolicy.rpResultWaitMs) {
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

    private fun failPendingOutgoingSmsForWriteFailure(callId: String, reason: String) {
        val pending = smsLock.withLock {
            pendingOutgoingSmsByCallId.remove(callId)?.also {
                pendingOutgoingSmsByRef.remove(it.ref)
            }
        } ?: return

        Rlog.w(
            tag,
            "Outgoing SMS SIP write failed before RP result: " +
                "reason=$reason ref=${pending.ref} callId=$callId",
        )

        try {
            pending.failCb()
        } catch (t: Throwable) {
            Rlog.d(tag, "Failed reporting outgoing SMS write failure", t)
        }
    }

    fun clearState(reason: String = "IMS state reset") {
        val pending = smsLock.withLock {
            val drained = pendingOutgoingSmsByCallId.values
                .distinctBy { it.callId }
                .toList()
            smsHeadersMap.clear()
            pendingOutgoingSmsByCallId.clear()
            pendingOutgoingSmsByRef.clear()
            drained
        }

        pending.forEach { outgoing ->
            responseCallbackRemover(outgoing.callId)
            Rlog.w(
                tag,
                "Failing pending outgoing SMS during state reset: " +
                    "reason=$reason ref=${outgoing.ref} callId=${outgoing.callId}",
            )
            try {
                outgoing.failCb()
            } catch (t: Throwable) {
                Rlog.d(tag, "Failed reporting outgoing SMS state reset", t)
            }
        }
    }

    fun handleSms(request: SipRequest): Int {
        val sms = request.body.SipSmsDecode()
        if (sms == null) {
            Rlog.w(tag, "Could not decode sms pdu")
            return 500
        }

        Rlog.d(tag, "Decoded SMS type=${sms.type} pduBytes=${sms.pdu?.size ?: 0}")
        when (sms.type) {
            SmsType.RP_DATA_FROM_NETWORK -> {
                val receivedCb = onSmsReceived
                if (receivedCb == null) {
                    Rlog.d(tag, "No onSmsReceived callback!")
                    return 500
                }

                val dest = request.headers["from"]!![0]
                    .getParams()
                    .component1()
                    .trimStart('<')
                    .trimEnd('>')
                val callId = request.headers["call-id"]!![0]
                val cseq = request.headers["cseq"]!![0]
                val token = smsLock.withLock {
                    val nextToken = smsToken++
                    smsHeadersMap[nextToken] = smsHeaders(dest, callId, cseq)
                    nextToken
                }

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


    /*
     * Write and flush a complete IMS SMS SIP frame to the socket writer.
     *
     * Keep logging the byte count and first line so carrier-specific MESSAGE
     * failures can be correlated with the exact request/response sent.
     */
    private fun writeSmsSipBytesWithFlush(
        writer: java.io.OutputStream,
        label: String,
        bytes: ByteArray,
    ): Boolean {
        val written = SipMessageWriter.write(
            tag = tag,
            writer = writer,
            bytes = bytes,
            label = label,
        )
        if (written) {
            val firstLine = bytes
                .toString(Charsets.US_ASCII)
                .lineSequence()
                .firstOrNull()
                .orEmpty()
            Rlog.d(
                tag,
                "SIP SMS write complete label=$label bytes=${bytes.size} firstLine=$firstLine",
            )
        }
        return written
    }

    fun sendSms(
        smsSmsc: String?,
        pdu: ByteArray,
        ref: Int,
        successCb: (() -> Unit),
        failCb: (() -> Unit),
    ) {
        if (!carrierSettings.smsIpEnabled(registrationTechProvider())) {
            Rlog.w(tag, "Carrier profile disables SMS over IMS on this access")
            failCb()
            return
        }
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
        Rlog.d(tag, "SMSC identity available=${smscIdentity != null}")

        val frameworkSmsc = normalizeSmscNumber(smsSmsc)
        val identitySmsc = normalizeSmscNumber(smscIdentity?.host)
        val managerSmsc = try {
            val smscStr = smsManager.smscAddress
            val parsed = normalizeSmscNumber(smscStr)
            Rlog.d(tag, "SMSC manager address available=${parsed != null}")
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
        val useSingTelSmsPolicy = carrierSettings.useSingTelStockPolicy(realm)

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

        val smsc = if (useSingTelSmsPolicy) carrierSettings.singtelSmsc() else rawSmsc

        // RP-DATA destination address. Passing an empty string makes
        // PhoneNumberUtils.numberToCalledPartyBCD("") return null and crashes
        // SipSmsEncodeSms(), so keep it null when we genuinely do not know it.
        val rpSmsc = smsc?.let { if (it.startsWith("+")) it else "+$it" }
        val data = SipSmsEncodeSms(ref.toByte(), rpSmsc, pdu)
        Rlog.d(
            tag,
            "Encoding outgoing IMS SMS bodyBytes=${data.size} " +
                "frameworkSmsc=${frameworkSmsc != null} identitySmsc=${identitySmsc != null} " +
                "managerSmsc=${managerSmsc != null}",
        )

        val smscSipIdentity = smscIdentity?.toString()?.let { normalizeSipTarget(it) }
        val requestUri = carrierSettings.smsRequestUri(realm, smsc, smscSipIdentity)
        val dest = carrierSettings.smsToUri(realm, requestUri, smsc, smscSipIdentity)
        if (useSingTelSmsPolicy) {
            Rlog.d(tag, "Using carrier-configured IMS SMS request and To URI shape")
        }

        val securityHeaders = if (carrierSettings.ipsecSupported) {
            """
                Supported: sec-agree, path
                Require: sec-agree
                Proxy-Require: sec-agree
            """.trimIndent()
        } else {
            "Supported: path"
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
                $securityHeaders
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

        Rlog.d(tag, "Sending ${msg.safeLogSummary()}")
        val writer = writerProvider()
        val writeLabel = "outgoing SMS MESSAGE ref=$ref"
        if (!writeSmsSipBytesWithFlush(writer, writeLabel, msg.toByteArray())) {
            responseCallbackRemover(callId)
            failPendingOutgoingSmsForWriteFailure(callId, writeLabel)
            sipWriteFailureListener(writeLabel)
        }
    }

    fun sendSmsAck(token: Int, ref: Int, error: Boolean) {
        Rlog.d(tag, "sending sms ack")
        val body = SipSmsEncodeAck(ref.toByte())
        val headers = smsLock.withLock { smsHeadersMap.remove(token) } ?: return

        // Do not send ACK on framework error. Should we send an error report?
        if (error) {
            return
        }

        val securityHeaders = if (carrierSettings.ipsecSupported) {
            """
                Proxy-Require: sec-agree
                Require: sec-agree
                Supported: path, gruu, sec-agree
            """.trimIndent()
        } else {
            "Supported: path"
        }
        val msg = SipRequest(
            SipMethod.MESSAGE,
            headers.dest,
            commonHeadersProvider() + """
                Cseq: ${headers.cseq}
                In-Reply-To: ${headers.callId}
                Content-Type: application/vnd.3gpp.sms
                $securityHeaders
                Allow: MESSAGE
                Request-Disposition: no-fork
                Accept-Contact: *;+g.3gpp.smsip
            """.toSipHeadersMap(),
            body,
        )

        // Ignore response.
        val callId = msg.headers["call-id"]!![0]
        responseCallbackSetter(callId) { true }

        Rlog.d(tag, "Sending ${msg.safeLogSummary()}")
        val writer = writerProvider()
        val writeLabel = "outgoing SMS ACK ref=$ref"
        if (!writeSmsSipBytesWithFlush(writer, writeLabel, msg.toByteArray())) {
            responseCallbackRemover(callId)
            sipWriteFailureListener(writeLabel)
        }
    }

    private fun normalizeSipTarget(raw: String): String =
        if (raw.startsWith("sip:", ignoreCase = true) || raw.startsWith("tel:", ignoreCase = true)) {
            raw
        } else {
            "sip:$raw"
        }
}
