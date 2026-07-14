package me.phh.sip

internal object SipIncomingInviteRequestFlowLogs {
    fun duplicateIncomingInviteRefreshLog(
        callId: String,
        cseq: String,
        finalResponseSent: Boolean,
        awaitingAck: Boolean,
        callStarted: Boolean,
    ): String =
        "Refreshing duplicate incoming INVITE for existing incoming dialog: " +
            "callId=$callId cseq=$cseq " +
            "finalResponseSent=$finalResponseSent " +
            "awaitingAck=$awaitingAck callStarted=$callStarted"

    fun explicitTryingLog(response: SipResponse): String =
        "Sending explicit 100 Trying on incoming request flow: ${response.safeLogSummary()}"
    fun abortedIncomingRtpSocketCloseFailedLog(): String =
        "Closing aborted incoming RTP socket failed"

    fun deferringIncomingMediaUntilFinalAckLog(): String =
        "Deferring incoming media threads until final ACK"

    fun reliableIncoming183Log(response: SipResponse): String =
        "Sending reliable incoming 183 for precondition offer: ${response.safeLogSummary()}"

    fun plainIncoming180Log(response: SipResponse): String =
        "Sending plain 180 Ringing on incoming request flow: ${response.safeLogSummary()}"

}
