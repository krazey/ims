// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.Rlog
import java.io.OutputStream
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SIP transaction callback router.
 *
 * This is intentionally small and behaviour-compatible with the dispatcher code
 * that currently lives in SipHandler. The first user should be SipHandler
 * itself; later transport classes can feed parsed messages into this class
 * without owning call/SMS/registration state directly.
 */
internal class SipDispatcher(
    private val tag: String,
) {
    private val lock = ReentrantLock()
    private var requestCallbacks: Map<SipMethod, (SipRequest) -> Int> = emptyMap()
    private var responseCallbacks: Map<String, (SipResponse) -> Boolean> = emptyMap()

    /**
     * SIP responses must be written back on the same transport flow that
     * delivered the request. This matters for incoming INVITE over the TCP
     * server socket: writing 180/200 to the registration socket may be ignored
     * by the P-CSCF.
     */
    private val requestWriters = ConcurrentHashMap<String, OutputStream>()

    fun setRequestCallback(method: SipMethod, cb: (SipRequest) -> Int) {
        lock.withLock { requestCallbacks += method to cb }
    }

    fun setResponseCallback(callId: String, cb: (SipResponse) -> Boolean) {
        lock.withLock { responseCallbacks += callId to cb }
    }

    fun clearCallbacks() {
        lock.withLock {
            requestCallbacks = emptyMap()
            responseCallbacks = emptyMap()
        }
    }

    fun clearWriters() {
        requestWriters.clear()
    }

    fun writerForCallId(callId: String): OutputStream? = requestWriters[callId]

    fun hasWriterForCallId(callId: String): Boolean = requestWriters.containsKey(callId)

    fun parseMessage(reader: SipReader, writer: OutputStream): Boolean {
        val msg = try {
            reader.parseMessage()
        } catch (e: SocketException) {
            Rlog.d(tag, "Got exception $e")
            if ("$e" == "java.net.SocketException: Try again") {
                // We sometimes seem to get EAGAIN.
                return true
            }
            throw e
        }

        Rlog.d(tag, "RObject() message $msg")

        if (msg is SipResponse) {
            return handleResponse(msg)
        }

        if (msg !is SipRequest) {
            Rlog.d(tag, "Got invalid message! Closing socket (except main)")
            return false
        }

        msg.headers["call-id"]?.getOrNull(0)?.let { callId ->
            requestWriters[callId] = writer
        }

        val requestCb = lock.withLock { requestCallbacks[msg.method] }
        var status = 200
        if (requestCb != null) {
            status = try {
                requestCb(msg)
            } catch (t: Throwable) {
                Rlog.e(
                    tag,
                    "Request handler for ${msg.method} crashed; replying 500 and keeping SIP transport alive",
                    t,
                )
                500
            }
        }

        if (status == 0) return true

        val responseHeaders = msg.headers.filter { (k, _) ->
            k in listOf("cseq", "via", "from", "to", "call-id")
        } + when (status) {
            486 -> "Reason: Q.850;cause=17;text=\"User busy\"".toSipHeadersMap()
            else -> emptyMap()
        }

        val reply = SipResponse(
            statusCode = status,
            statusString = when (status) {
                100 -> "Trying"
                200 -> "OK"
                481 -> "Call/Transaction Does Not Exist"
                486 -> "Busy Here"
                487 -> "Request Terminated"
                488 -> "Not Acceptable Here"
                500 -> "Server Internal Error"
                603 -> "Decline"
                else -> "ERROR"
            },
            headersParam = responseHeaders,
        )

        Rlog.d(tag, "Replying back with $reply")
        synchronized(writer) {
            writer.write(reply.toByteArray())
            writer.flush()
        }
        return true
    }

    fun handleResponse(response: SipResponse): Boolean {
        val callId = response.headers["call-id"]?.get(0)
        if (callId == null) {
            // Message without call-id should never happen, close connection.
            return false
        }

        val responseCb = lock.withLock { responseCallbacks[callId] }
            ?: return true

        if (responseCb(response)) {
            lock.withLock { responseCallbacks -= callId }
        }
        return true
    }
}
