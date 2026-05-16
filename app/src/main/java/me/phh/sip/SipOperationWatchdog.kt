//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.net.IpSecManager
import android.telephony.Rlog
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object SipOperationWatchdog {
    fun connectSipSocket(
        logTag: String,
        connection: SipConnection,
        remoteAddress: InetAddress,
        remotePort: Int,
        label: String,
        timeoutMs: Long = 10_000L,
    ) {
        val finished = AtomicBoolean(false)
        var failure: Throwable? = null
        val connectThread = thread(name = "PhhImsSocketConnect-$label", isDaemon = true) {
            try {
                Rlog.d(logTag, "$label SIP socket connect start remote=$remoteAddress:$remotePort")
                connection.connect(remotePort)
            } catch (t: Throwable) {
                failure = t
            } finally {
                finished.set(true)
            }
        }

        connectThread.join(timeoutMs)
        if (!finished.get()) {
            val reason = "$label SIP socket connect timed out after ${timeoutMs}ms to $remoteAddress:$remotePort"
            Rlog.w(logTag, reason)
            try {
                connection.close()
            } catch (t: Throwable) {
                Rlog.d(logTag, "close timed-out $label SIP socket failed", t)
            }
            connectThread.join(1000L)
            throw SocketTimeoutException(reason)
        }

        failure?.let { throw it }
        Rlog.d(logTag, "$label SIP socket connect completed remote=$remoteAddress:$remotePort")
    }

    fun allocateSecurityParameterIndex(
        logTag: String,
        ipSecManager: IpSecManager,
        label: String,
        address: InetAddress,
        requestedSpi: Int? = null,
        timeoutMs: Long = 10_000L,
    ): IpSecManager.SecurityParameterIndex {
        val finished = AtomicBoolean(false)
        var allocated: IpSecManager.SecurityParameterIndex? = null
        var failure: Throwable? = null
        val allocThread = thread(name = "PhhImsIpsecAllocate-$label", isDaemon = true) {
            try {
                Rlog.d(
                    logTag,
                    "$label allocation start address=$address" +
                        if (requestedSpi != null) " requestedSpi=$requestedSpi" else "",
                )
                allocated = if (requestedSpi != null) {
                    ipSecManager.allocateSecurityParameterIndex(address, requestedSpi)
                } else {
                    ipSecManager.allocateSecurityParameterIndex(address)
                }
            } catch (t: Throwable) {
                failure = t
            } finally {
                finished.set(true)
            }
        }

        allocThread.join(timeoutMs)
        if (!finished.get()) {
            val reason = "$label allocation timed out after ${timeoutMs}ms address=$address" +
                if (requestedSpi != null) " requestedSpi=$requestedSpi" else ""
            Rlog.w(logTag, reason)
            throw SocketTimeoutException(reason)
        }

        failure?.let { throw it }
        val result = allocated ?: throw SocketTimeoutException("$label allocation returned no SPI")
        Rlog.d(logTag, "$label allocation completed spi=${result.spi}")
        return result
    }
}
