package me.phh.sip

internal object SipRemoteTerminationResponses {
    fun emptyBodyHeaders(): SipHeadersMap = "Content-Length: 0".toSipHeadersMap()

    fun ok(headers: SipHeadersMap): SipResponse = SipResponse(
        statusCode = 200,
        statusString = "OK",
        headersParam = headers,
        autofill = false
    )

    fun inviteCseqFromCancel(request: SipRequest): String =
        request.headers["cseq"]?.getOrNull(0)
            ?.replace(Regex("\\bCANCEL\\b", RegexOption.IGNORE_CASE), "INVITE")
            ?: "1 INVITE"

    fun cancelledInviteHeaders(originalInviteCseq: String): SipHeadersMap = (
        "CSeq: $originalInviteCseq\\n" +
            "Content-Length: 0\\n"
        ).toSipHeadersMap()

    fun requestTerminated(headers: SipHeadersMap): SipResponse = SipResponse(
        statusCode = 487,
        statusString = "Request Terminated",
        headersParam = headers,
        autofill = false
    )
}
