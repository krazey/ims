package me.phh.sip

import android.telephony.Rlog

internal object SipOutgoingInviteResponseGuards {
    fun shouldIgnoreStaleResponse(
        logTag: String,
        response: SipResponse,
        expectedCallId: String,
        activeCallIdForResponse: String?,
        pendingCallIdForResponse: String?,
    ): Boolean {
        val responseCallId = response.headers["call-id"]?.getOrNull(0).orEmpty()
        val responseCseqForLog = response.headers["cseq"]?.getOrNull(0)
        if (responseCallId != expectedCallId ||
            (activeCallIdForResponse != responseCallId && pendingCallIdForResponse != responseCallId)) {
            Rlog.w(
                logTag,
                "Ignoring stale outgoing response: " +
                    "status=${response.statusCode} ${response.statusString} " +
                    "cseq=$responseCseqForLog callId=$responseCallId " +
                    "active=$activeCallIdForResponse pending=$pendingCallIdForResponse " +
                    "expected=$expectedCallId",
            )
            return true
        }
        return false
    }
}

internal data class OutgoingAckOrByeResponseResult(
    val callbackResult: Boolean,
    val clearDialogCallId: String? = null,
    val clearPendingReason: String? = null,
)

internal object SipOutgoingInviteAckByeResponses {

    fun fallbackByeResponseCleanupReason(
        cseq: String,
        statusCode: Int,
    ): String =
        "outgoing BYE response $cseq $statusCode"

    fun handle(
        logTag: String,
        response: SipResponse,
        cseq: String,
    ): OutgoingAckOrByeResponseResult? {
        if (cseq.contains("ACK")) return OutgoingAckOrByeResponseResult(callbackResult = false)
        if (!cseq.contains("BYE")) return null

        val byeCallId = response.callIdOrEmpty()
        if (response.statusCode in 200..299) {
            Rlog.d(logTag, "Outgoing BYE accepted; clearing dialog callId=$byeCallId cseq=$cseq")
        } else if (response.statusCode >= 300) {
            Rlog.w(
                logTag,
                "Outgoing BYE failed; clearing local dialog anyway: " +
                    "status=${response.statusCode} ${response.statusString} cseq=$cseq callId=$byeCallId",
            )
        } else {
            return OutgoingAckOrByeResponseResult(callbackResult = false)
        }

        return OutgoingAckOrByeResponseResult(
            callbackResult = true,
            clearDialogCallId = byeCallId,
            clearPendingReason = "outgoing BYE response $cseq ${response.statusCode}",
        )
    }
}

internal data class OutgoingPrackResponseState(
    val response: SipResponse,
    val cseq: String,
    val rseqHandled: Boolean,
    val callbackResult: Boolean? = null,
    val clearRespInFlight: Boolean = false,
)

internal object SipOutgoingInvitePrackResponses {
    fun handle(
        logTag: String,
        response: SipResponse,
        cseq: String,
        prackedReliableProvisionals: MutableSet<String>,
        savedProvisional: SipResponse?,
    ): OutgoingPrackResponseState {
        if (!cseq.contains("PRACK")) {
            return OutgoingPrackResponseState(
                response = response,
                cseq = cseq,
                rseqHandled = false,
            )
        }

        if (savedProvisional == null) {
            Rlog.w(
                logTag,
                "Ignoring PRACK response without pending provisional response: " +
                    "status=${response.statusCode} ${response.statusString} cseq=$cseq",
            )
            return OutgoingPrackResponseState(
                response = response,
                cseq = cseq,
                rseqHandled = false,
                callbackResult = false,
            )
        }

        if (response.statusCode >= 300) {
            Rlog.w(
                logTag,
                "PRACK failed for pending provisional response: " +
                    "status=${response.statusCode} ${response.statusString} cseq=$cseq",
            )
            val failedReliableKey =
                "${savedProvisional.headers["rseq"]?.getOrNull(0).orEmpty()} " +
                    savedProvisional.headers["cseq"]?.getOrNull(0).orEmpty()
            if (prackedReliableProvisionals.remove(failedReliableKey)) {
                Rlog.w(
                    logTag,
                    "Removing failed PRACK key so retransmitted reliable provisional can be retried: $failedReliableKey",
                )
            }
            return OutgoingPrackResponseState(
                response = response,
                cseq = cseq,
                rseqHandled = false,
                callbackResult = false,
                clearRespInFlight = true,
            )
        }

        return OutgoingPrackResponseState(
            response = savedProvisional,
            cseq = savedProvisional.headers["cseq"]!![0],
            rseqHandled = true,
            clearRespInFlight = true,
        )
    }
}

internal data class OutgoingProgressNotification(
    val extras: Map<String, String>,
)

internal object SipOutgoingInviteProgressResponses {

    fun staleOutgoingDialogFailureLog(
        response: SipResponse,
        failedCseq: String,
        failedCallId: String,
        activeCallId: String?,
        pendingCallId: String?,
    ): String =
        "Ignoring stale outgoing dialog failure: " +
            "status=${response.statusCode} ${response.statusString} " +
            "cseq=$failedCseq callId=$failedCallId active=$activeCallId pending=$pendingCallId"

    fun outgoingDialogRequestFailedLog(
        response: SipResponse,
        failedCseq: String,
        failedCallId: String,
    ): String =
        "Outgoing dialog request failed: " +
            "status=${response.statusCode} ${response.statusString} " +
            "cseq=$failedCseq callId=$failedCallId " +
            "diagnosticHeaders=${SipCarrierDiagnostics.responseHeaders(response)}"

    fun outgoingDialogFailureCleanupReason(): String =
        "outgoing dialog failure"

    fun earlyOutgoingInDialogRequestFailedLog(failedCallId: String): String =
        "Early outgoing in-dialog request failed; cancelling pending INVITE callId=$failedCallId"

    fun earlyDialogRequestFailedCancelReason(
        failedCseq: String,
        statusCode: Int,
    ): String =
        "early dialog request failed: $failedCseq $statusCode"

    fun outgoingDialogFailurePendingCleanupReason(
        failedCseq: String,
        statusCode: Int,
    ): String =
        "outgoing dialog failure $failedCseq $statusCode"

    fun outgoingDialogFailureCancellationExtras(
        response: SipResponse,
        failedCseq: String,
    ): Map<String, String> =
        mapOf(
            "statusCode" to response.statusCode.toString(),
            "statusString" to response.statusString,
            "cseq" to failedCseq,
        )

    fun outgoingFailureRoutingExtras(
        initialInviteFailed: Boolean,
        csRetry: Boolean,
    ): Map<String, String> {
        val extras = mutableMapOf<String, String>()
        if (initialInviteFailed) {
            extras["callStartFailed"] = "true"
            extras["outgoingCall"] = "true"
        }
        if (csRetry) {
            extras["csRetry"] = "true"
        }
        return extras
    }

    fun progressNotification(
        logTag: String,
        response: SipResponse,
    ): OutgoingProgressNotification? {
        Rlog.d(logTag, "Invite got status ${response.statusCode} = ${response.statusString}")
        if (response.statusCode !in 180..199) return null

        val progressCseq = response.headers["cseq"]?.getOrNull(0).orEmpty()
        val progressHasSdp = response.headers["content-type"]?.getOrNull(0)
            ?.equals("application/sdp", ignoreCase = true) == true

        if (!progressCseq.contains("INVITE", ignoreCase = true) || progressHasSdp) return null

        Rlog.d(
            logTag,
            "Outgoing call progressing without SDP: " +
                "status=${response.statusCode} ${response.statusString} cseq=$progressCseq",
        )
        return OutgoingProgressNotification(
            extras = mapOf(
                "call-id" to response.callIdOrEmpty(),
                "statusCode" to response.statusCode.toString(),
                "statusString" to response.statusString,
                "cseq" to progressCseq,
                "local-ringback" to "true",
            ),
        )
    }
}

internal data class OutgoingReliableProvisionalDecision(
    val reliableKey: String,
    val callbackResult: Boolean? = null,
)

internal object SipOutgoingInviteReliableProvisionals {

    fun syncingPrackCseqAllocatorLog(
        allocatorNextCseq: Int,
        currentCallNextCseq: Int,
        reliableKey: String,
    ): String =
        "Syncing outgoing PRACK CSeq allocator from current call: " +
            "allocator=$allocatorNextCseq currentCallNext=$currentCallNextCseq key=$reliableKey"

    fun prackConsumedLocalCseqLog(
        prackCseq: Int,
        nextAllocatorCseq: Int,
        currentCallNextCseq: Int?,
        reliableKey: String,
    ): String =
        "Outgoing PRACK consumed local CSeq=$prackCseq " +
            "nextAllocatorCseq=$nextAllocatorCseq " +
            "currentCallNextCseq=$currentCallNextCseq key=$reliableKey"

    fun classify(
        logTag: String,
        response: SipResponse,
        rseqHandled: Boolean,
        prackedReliableProvisionals: MutableSet<String>,
    ): OutgoingReliableProvisionalDecision? {
        if (response.headers["rseq"]?.isNotEmpty() != true || rseqHandled) return null

        val reliableKey =
            "${response.headers["rseq"]?.getOrNull(0).orEmpty()} " +
                response.headers["cseq"]?.getOrNull(0).orEmpty()
        if (!prackedReliableProvisionals.add(reliableKey)) {
            Rlog.w(logTag, "Ignoring duplicate reliable provisional response already PRACKed: $reliableKey")
            return OutgoingReliableProvisionalDecision(
                reliableKey = reliableKey,
                callbackResult = false,
            )
        }

        return OutgoingReliableProvisionalDecision(
            reliableKey = reliableKey,
        )
    }
}

internal object SipOutgoingInviteUpdateResponses {
    fun handleIfNeeded(response: SipResponse): Boolean? {
        // This isn't the answer to our INVITE, but to our later precondition UPDATE.
        // TODO: Actually check CSeq number.
        if (response.headers["cseq"]?.get(0)?.contains("UPDATE") == true) {
            if (response.statusCode == 200) {
                // Nothing to do here, we've already upgraded the call with the new SDP, everything's fine.
                return false
            }
        }
        return null
    }
}
