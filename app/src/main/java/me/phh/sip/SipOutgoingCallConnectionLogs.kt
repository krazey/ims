package me.phh.sip

internal object SipOutgoingCallConnectionLogs {
    fun staleConnectedNotifyLog(
        callId: String,
        activeCallId: String,
        reason: String,
    ): String =
        "Not notifying outgoing connected for stale call: callId=$callId active=$activeCallId reason=$reason"

    fun rtpBeforeFinalAnswerLog(
        callId: String,
        reason: String,
    ): String =
        "Outgoing RTP seen before final answer; wait before connected notify callId=$callId reason=$reason"

    fun noPostAnswerRtpYetLog(
        callId: String,
        reason: String,
    ): String =
        "Outgoing final answer received but no post-answer remote RTP yet; " +
            "keeping Android call in dialing state callId=$callId reason=$reason"

    fun connectedAfterRemoteRtpLog(
        callId: String,
        reason: String,
    ): String =
        "Outgoing call connected after remote RTP: callId=$callId reason=$reason"

    fun duplicateConnectedNotifyLog(
        callId: String,
        reason: String,
    ): String =
        "Ignoring duplicate outgoing connected notification: " +
            "callId=${callId.ifBlank { "<blank>" }} reason=$reason"

    fun postAnswerRtpTimeoutLog(
        timeoutMs: Long,
        callId: String,
    ): String =
        "No post-answer RTP within ${timeoutMs}ms for outgoing call; " +
            "terminating no-media dialog as network reject callId=$callId"

    fun postAnswerRtpTimeoutReason(): String =
        "post-answer RTP timeout"

    fun failedByeForNoMediaTimeoutLog(callId: String): String =
        "Failed to send BYE for outgoing no-media timeout callId=$callId"

    fun postAnswerRtpTimeoutCancellationExtras(callId: String): Map<String, String> =
        mapOf(
            "call-id" to callId,
            "statusCode" to "480",
            "statusString" to "No post-answer RTP",
            "remoteNoMediaRelease" to "true",
        )

    fun postAnswerRtpTimeoutFailedLog(callId: String): String =
        "Outgoing post-answer RTP timeout failed callId=$callId"
}
