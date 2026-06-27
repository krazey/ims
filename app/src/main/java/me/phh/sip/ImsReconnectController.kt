// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.net.Network
import android.telephony.Rlog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal class ImsReconnectController(
    private val tag: String,
    private val currentNetwork: () -> Network?,
    private val setCurrentNetwork: (Network) -> Unit,
    private val reportFailure: () -> Unit,
    private val dropConnection: (String, Boolean) -> Unit,
    private val shouldKeepRegistrationDuringReconnect: (String, Network?) -> Boolean,
    private val connect: () -> Unit,
) {
    private data class PendingReconnectRequest(
        val reason: String,
        val newNetwork: Network?,
        val delayMs: Long,
        val generation: Int,
    )

    private val reconnecting = AtomicBoolean(false)
    private val reconnectRetryScheduled = AtomicBoolean(false)
    private val failureCount = AtomicInteger(0)
    private val generation = AtomicInteger(0)
    private val pendingLock = Object()
    private var pendingRequest: PendingReconnectRequest? = null

    fun markConnected() {
        failureCount.set(0)
    }

    fun invalidatePendingReconnects(reason: String) {
        val newGeneration = generation.incrementAndGet()
        Rlog.d(tag, "Invalidated pending IMS reconnects: $reason generation=$newGeneration")
    }

    fun isReconnecting(): Boolean = reconnecting.get()

    fun scheduleReconnectRetry(reason: String, delayMs: Long) {
        val retryNetwork = currentNetwork()
        if (retryNetwork == null) {
            Rlog.w(tag, "Cannot schedule IMS reconnect retry without a Network: $reason")
            return
        }

        val retryGeneration = generation.get()
        if (!reconnectRetryScheduled.compareAndSet(false, true)) {
            Rlog.w(tag, "IMS reconnect retry already scheduled, ignore: $reason")
            return
        }

        thread {
            try {
                Rlog.w(tag, "IMS reconnect retry in ${delayMs}ms: $reason")
                Thread.sleep(delayMs)

                // Clear before reconnectIms(), so a failed retry may schedule the next
                // backoff attempt.
                reconnectRetryScheduled.set(false)

                val activeNetwork = currentNetwork()
                if (retryGeneration != generation.get() || activeNetwork != retryNetwork) {
                    Rlog.w(
                        tag,
                        "Skipping stale IMS reconnect retry: $reason " +
                            "retryGeneration=$retryGeneration currentGeneration=${generation.get()} " +
                            "retryNetwork=$retryNetwork currentNetwork=$activeNetwork",
                    )
                    return@thread
                }

                reconnectIms("retry after failed SIP connect: $reason", retryNetwork, delayMs = 0L)
            } catch (t: Throwable) {
                Rlog.e(tag, "IMS reconnect retry failed to start: $reason", t)
            } finally {
                reconnectRetryScheduled.set(false)
            }
        }
    }

    fun failConnectAndRetry(reason: String, baseDelayMs: Long = 5000L) {
        val failures = failureCount.incrementAndGet().coerceAtMost(6)
        val delayMs = (baseDelayMs * (1L shl (failures - 1))).coerceAtMost(120_000L)

        Rlog.w(tag, "$reason; reporting deregistered and retrying IMS registration in ${delayMs}ms")
        reportFailure()
        scheduleReconnectRetry(reason, delayMs)
    }

    fun reconnectIms(reason: String, newNetwork: Network? = null, delayMs: Long = 1000L) {
        val request = PendingReconnectRequest(
            reason = reason,
            newNetwork = newNetwork,
            delayMs = delayMs,
            generation = generation.incrementAndGet(),
        )

        synchronized(pendingLock) {
            pendingRequest = request
        }

        if (reconnecting.get()) {
            Rlog.w(tag, "IMS reconnect already running, queued: $reason")
        }

        startReconnectWorkerIfNeeded()
    }

    private fun takePendingReconnectRequest(): PendingReconnectRequest? {
        return synchronized(pendingLock) {
            val request = pendingRequest
            pendingRequest = null
            request
        }
    }

    private fun hasPendingReconnectRequest(): Boolean {
        return synchronized(pendingLock) {
            pendingRequest != null
        }
    }

    private fun startReconnectWorkerIfNeeded() {
        if (!reconnecting.compareAndSet(false, true)) {
            return
        }

        thread {
            try {
                while (true) {
                    val request = takePendingReconnectRequest() ?: break

                    try {
                        val keepRegistrationDuringReconnect =
                            shouldKeepRegistrationDuringReconnect(request.reason, request.newNetwork)
                        Rlog.w(
                            tag,
                            "Reconnecting IMS: ${request.reason}" +
                                if (keepRegistrationDuringReconnect) {
                                    " while keeping framework IMS registration stable"
                                } else {
                                    ""
                                },
                        )
                        dropConnection(
                            request.reason,
                            !keepRegistrationDuringReconnect,
                        )

                        if (request.newNetwork != null) {
                            setCurrentNetwork(request.newNetwork)
                        }

                        Thread.sleep(request.delayMs)

                        if (request.generation != generation.get()) {
                            Rlog.w(
                                tag,
                                "Skipping stale IMS reconnect: ${request.reason} " +
                                    "requestGeneration=${request.generation} " +
                                    "currentGeneration=${generation.get()}",
                            )
                            continue
                        }

                        if (currentNetwork() == null) {
                            Rlog.w(tag, "Cannot reconnect IMS without a Network")
                            reportFailure()
                            continue
                        }

                        connect()
                    } catch (t: Throwable) {
                        Rlog.e(tag, "IMS reconnect failed: ${request.reason}", t)
                        failConnectAndRetry("IMS reconnect failed: ${request.reason}")
                    }
                }
            } finally {
                reconnecting.set(false)

                // Close the small race where a reconnect request is queued after the
                // worker drained the queue but before reconnecting was cleared.
                if (hasPendingReconnectRequest()) {
                    startReconnectWorkerIfNeeded()
                }
            }
        }
    }
}
