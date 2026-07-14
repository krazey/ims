package me.phh.sip

import android.telephony.Rlog
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal data class PendingOutgoingInvite(
    val callId: String,
    val destination: String,
    val headers: SipHeadersMap,
    val rtpSocket: DatagramSocket,
    val body: ByteArray,
    val retriedAfter422: AtomicBoolean = AtomicBoolean(false),
    val retriedAfterIllegalSdp: AtomicBoolean = AtomicBoolean(false),
    val cancelSent: AtomicBoolean = AtomicBoolean(false),
)

internal data class InitialOutgoingInviteSendState(
    val callId: String,
    val outgoingDialogNextCseq: AtomicInteger,
)

internal data class InitialOutgoingInvitePreparedState(
    val pendingInvite: PendingOutgoingInvite,
    val sendState: InitialOutgoingInviteSendState,
)

internal object SipOutgoingInviteInitialSend {
    fun prepare(
        msg: SipRequest,
        destination: String,
        rtpSocket: DatagramSocket,
        body: ByteArray,
    ): InitialOutgoingInvitePreparedState {
        val outgoingInviteCallId = msg.headers["call-id"]!![0]
        val outgoingInviteCseq = msg.headers["cseq"]?.getOrNull(0)
            ?.substringBefore(" ")
            ?.toIntOrNull()
            ?: 1
        val outgoingDialogNextCseq = AtomicInteger(outgoingInviteCseq + 1)
        val pendingInvite = PendingOutgoingInvite(
            callId = outgoingInviteCallId,
            destination = destination,
            headers = msg.headers,
            rtpSocket = rtpSocket,
            body = body,
        )

        return InitialOutgoingInvitePreparedState(
            pendingInvite = pendingInvite,
            sendState = InitialOutgoingInviteSendState(
                callId = outgoingInviteCallId,
                outgoingDialogNextCseq = outgoingDialogNextCseq,
            ),
        )
    }

    fun write(
        logTag: String,
        msg: SipRequest,
        phoneNumber: String,
        normalizedPhoneNumber: String,
        destination: String,
        rtpSocket: DatagramSocket,
        body: ByteArray,
        debugContext: (String) -> String,
        writeBytes: (ByteArray) -> Unit,
    ) {
        val outgoingInviteCallId = msg.headers["call-id"]!![0]
        Rlog.w(
            logTag,
            "Outgoing INVITE send context " +
                debugContext(
                    "${msg.safeLogSummary()} rtpLocalPort=${rtpSocket.localPort} " +
                        "sdpBytes=${body.size}"
                ),
        )
        Rlog.d(logTag, "Sending ${msg.safeLogSummary()}")
        writeBytes(msg.toByteArray())
    }
}
