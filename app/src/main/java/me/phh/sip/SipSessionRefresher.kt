// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.os.Handler
import android.telephony.Rlog

internal data class SipSessionRefreshDialog(
    val destination: String,
    val requestHeaders: SipHeadersMap,
)

/**
 * Owns RFC 4028 local-refresh scheduling and transaction handling.
 *
 * Dialog lookup, SIP writes, and IMS recovery stay injected so this helper
 * does not own call slots, sockets, or transport lifecycle.
 */
internal class SipSessionRefresher(
    private val tag: String,
    private val handler: Handler,
    private val dialogProvider: (String) -> SipSessionRefreshDialog?,
    private val writeRequest: (String, String, ByteArray) -> Boolean,
    private val responseCallbackSetter: (
        String,
        Int,
        SipMethod,
        (SipResponse) -> Boolean,
    ) -> Unit,
    private val responseCallbackRemover: (String, Int, SipMethod) -> Unit,
    private val reconnectIms: (String) -> Unit,
) {
    companion object {
        private const val MIN_INTERVAL_SECONDS = 90
        private const val RESPONSE_TIMEOUT_MS = 32_000L
        private const val RETRY_DELAY_MS = 5_000L
        private const val MAX_ATTEMPTS = 2
    }

    private data class State(
        val intervalSeconds: Int,
        val generation: Int,
        val attempt: Int,
        val cseqNumber: Int? = null,
    )

    private val lock = Any()
    private val states = mutableMapOf<String, State>()
    private var nextGeneration = 1

    fun updateFromHeaders(
        callId: String,
        headers: SipHeadersMap,
        localRefresher: String,
        defaultRefresher: String? = null,
    ) {
        val selection = SipSessionTimerNegotiation.selectionFromHeaders(
            headers = headers,
            defaultRefresher = defaultRefresher,
        ) ?: return
        update(callId, selection, localRefresher)
    }

    fun updateFromIncomingRequest(
        callId: String,
        requestHeaders: SipHeadersMap,
    ) {
        updateFromHeaders(
            callId = callId,
            headers = SipSessionTimerNegotiation.responseHeadersForIncomingRequest(
                requestHeaders = requestHeaders,
                logTag = tag,
            ),
            localRefresher = "uas",
        )
    }

    fun update(
        callId: String,
        selection: SipSessionTimerSelection,
        localRefresher: String,
    ) {
        if (!selection.refresher.equals(localRefresher, ignoreCase = true)) {
            cancel(callId, "peer is refresher=${selection.refresher}")
            return
        }
        schedule(
            callId = callId,
            intervalSeconds = selection.intervalSeconds,
            attempt = 0,
            delayMs = refreshDelayMs(selection.intervalSeconds),
        )
    }

    fun cancel(callId: String, reason: String) {
        val old = synchronized(lock) { states.remove(callId) }
        old?.cseqNumber?.let { cseq ->
            responseCallbackRemover(callId, cseq, SipMethod.UPDATE)
        }
        if (old != null) {
            Rlog.d(tag, "Cancelled session refresh: callId=$callId reason=$reason")
        }
    }

    fun cancelAll(reason: String) {
        val oldStates = synchronized(lock) {
            states.toMap().also { states.clear() }
        }
        oldStates.forEach { (callId, state) ->
            state.cseqNumber?.let { cseq ->
                responseCallbackRemover(callId, cseq, SipMethod.UPDATE)
            }
        }
        if (oldStates.isNotEmpty()) {
            Rlog.d(tag, "Cancelled ${oldStates.size} session refresh timer(s): $reason")
        }
    }

    private fun schedule(
        callId: String,
        intervalSeconds: Int,
        attempt: Int,
        delayMs: Long,
    ) {
        val old: State?
        val state: State
        synchronized(lock) {
            old = states[callId]
            state = State(
                intervalSeconds = intervalSeconds.coerceAtLeast(MIN_INTERVAL_SECONDS),
                generation = nextGeneration++,
                attempt = attempt,
            )
            states[callId] = state
        }
        old?.cseqNumber?.let { cseq ->
            responseCallbackRemover(callId, cseq, SipMethod.UPDATE)
        }
        Rlog.d(
            tag,
            "Scheduled session refresh: callId=$callId " +
                "interval=${state.intervalSeconds}s delayMs=$delayMs attempt=$attempt",
        )
        handler.postDelayed(
            { sendIfCurrent(callId, state.generation) },
            delayMs,
        )
    }

    private fun sendIfCurrent(callId: String, generation: Int) {
        val state = currentState(callId, generation) ?: return
        val dialog = dialogProvider(callId) ?: run {
            cancel(callId, "dialog no longer exists")
            return
        }
        val request = refreshRequest(dialog, state.intervalSeconds)
        val cseq = request.headers["cseq"]
            ?.firstOrNull()
            ?.substringBefore(' ')
            ?.toIntOrNull()
            ?: run {
                cancel(callId, "refresh CSeq missing")
                return
            }
        val inFlight = state.copy(cseqNumber = cseq)
        synchronized(lock) {
            if (states[callId] != state) return
            states[callId] = inFlight
        }
        responseCallbackSetter(callId, cseq, SipMethod.UPDATE) { response ->
            handleResponse(callId, inFlight, response)
        }
        if (!writeRequest(
                callId,
                "session refresh UPDATE callId=$callId cseq=$cseq",
                request.toByteArray(),
            )
        ) {
            responseCallbackRemover(callId, cseq, SipMethod.UPDATE)
            cancel(callId, "session refresh write failed")
            reconnectIms("session refresh UPDATE write failed")
            return
        }
        handler.postDelayed(
            { handleTimeout(callId, inFlight) },
            RESPONSE_TIMEOUT_MS,
        )
    }

    private fun handleResponse(
        callId: String,
        state: State,
        response: SipResponse,
    ): Boolean {
        if (!isCurrent(callId, state)) return true
        if (response.statusCode in 100..199) return false

        return when (response.statusCode) {
            in 200..299 -> {
                val selection = SipSessionTimerNegotiation.selectionFromHeaders(
                    headers = response.headers,
                    defaultRefresher = "uac",
                )
                if (selection == null) {
                    cancel(callId, "refresh 2xx omitted Session-Expires")
                } else {
                    update(callId, selection, localRefresher = "uac")
                }
                true
            }
            422 -> {
                val interval = maxOf(
                    state.intervalSeconds,
                    SipSessionTimerNegotiation.minimumIntervalFromResponse(
                        response.headers,
                    ) ?: state.intervalSeconds,
                )
                retryOrRecover(callId, state, interval, "rejected with 422")
                true
            }
            else -> {
                retryOrRecover(
                    callId,
                    state,
                    state.intervalSeconds,
                    "failed ${response.statusCode}",
                )
                true
            }
        }
    }

    private fun handleTimeout(callId: String, state: State) {
        if (!isCurrent(callId, state)) return
        state.cseqNumber?.let { cseq ->
            responseCallbackRemover(callId, cseq, SipMethod.UPDATE)
        }
        retryOrRecover(callId, state, state.intervalSeconds, "response timeout")
    }

    private fun retryOrRecover(
        callId: String,
        state: State,
        intervalSeconds: Int,
        reason: String,
    ) {
        if (state.attempt + 1 < MAX_ATTEMPTS) {
            schedule(
                callId = callId,
                intervalSeconds = intervalSeconds,
                attempt = state.attempt + 1,
                delayMs = RETRY_DELAY_MS,
            )
            return
        }
        cancel(callId, "session refresh $reason")
        reconnectIms("session refresh $reason")
    }

    private fun currentState(callId: String, generation: Int): State? =
        synchronized(lock) {
            states[callId]?.takeIf { it.generation == generation }
        }

    private fun isCurrent(callId: String, state: State): Boolean =
        synchronized(lock) { states[callId] == state }

    private fun refreshRequest(
        dialog: SipSessionRefreshDialog,
        intervalSeconds: Int,
    ): SipRequest {
        val supported = (dialog.requestHeaders["supported"].orEmpty()
            .flatMap { it.split(',') } + "timer")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val headers = dialog.requestHeaders - "session-expires" - "min-se" + mapOf(
            "supported" to supported,
            "session-expires" to listOf(
                "${intervalSeconds.coerceAtLeast(MIN_INTERVAL_SECONDS)};refresher=uac",
            ),
            "min-se" to listOf(MIN_INTERVAL_SECONDS.toString()),
        )
        return SipRequest(
            method = SipMethod.UPDATE,
            destination = dialog.destination,
            headersParam = headers,
        )
    }

    private fun refreshDelayMs(intervalSeconds: Int): Long =
        intervalSeconds.coerceAtLeast(MIN_INTERVAL_SECONDS).toLong() * 500L
}
