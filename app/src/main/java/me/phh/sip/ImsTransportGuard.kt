package me.phh.sip

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.telephony.Rlog
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps IMS bearer/lifetime sanity separate from SIP dialog handling.
 *
 * SipHandler owns SIP state.  This helper owns the small piece of transport
 * state that decides whether the current IMS Network/local address is still
 * usable for a new outgoing SIP/RTP session while Android is moving between
 * IWLAN and WWAN.
 */
internal class ImsTransportGuard(
    private val tag: String,
    private val handler: Handler,
    private val connectivityManager: ConnectivityManager,
    private val actions: Actions,
) {
    internal interface Actions {
        fun currentNetwork(): Network?
        fun isSocketInitialized(): Boolean

        fun isImsReady(): Boolean
        fun setImsReadyForTransportSuppression(ready: Boolean)
        fun notifyImsFailure()
        fun markImsReady(reason: String)

        fun hasActiveOrPendingCall(): Boolean
        fun setPendingReconnectAfterActiveCall(reason: String)
        fun activeOrPendingCallSummary(): String

        fun invalidatePendingReconnects(reason: String)
        fun dropImsConnection(reason: String)
        fun setAbandonedBecauseOfNoPcscf()
        fun scheduleImsNetworkRequestRestart(reason: String, delayMs: Long)
    }

    private val suspendedGeneration = AtomicInteger(0)
    private var readySuppressedByNetworkSuspension = false

    fun isUsableForOutgoingCall(localAddress: InetAddress?, reason: String): Boolean {
        val currentNetwork = actions.currentNetwork()
        if (currentNetwork == null) {
            Rlog.w(tag, "No IMS network while checking transport usability: $reason")
            return false
        }

        if (localAddress == null) {
            Rlog.w(tag, "No IMS local address while checking transport usability: $reason")
            return false
        }

        val caps = try {
            connectivityManager.getNetworkCapabilities(currentNetwork)
        } catch (t: Throwable) {
            Rlog.w(tag, "Failed to read IMS NetworkCapabilities: $reason", t)
            null
        }

        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED) != true) {
            Rlog.w(tag, "IMS network is suspended/stale: network=$currentNetwork caps=$caps reason=$reason")
            return false
        }

        val lp = try {
            connectivityManager.getLinkProperties(currentNetwork)
        } catch (t: Throwable) {
            Rlog.w(tag, "Failed to read IMS LinkProperties while checking local address: $reason", t)
            null
        }

        val present = lp?.linkAddresses?.any { it.address == localAddress } == true
        if (!present) {
            Rlog.w(
                tag,
                "IMS local address is stale: network=$currentNetwork localAddr=$localAddress " +
                    "iface=${lp?.interfaceName} linkAddresses=${lp?.linkAddresses} reason=$reason",
            )
        }
        return present
    }

    fun onCapabilitiesChanged(callbackNetwork: Network, caps: NetworkCapabilities) {
        if (actions.currentNetwork() != callbackNetwork) {
            return
        }

        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) {
            handleNotSuspended(callbackNetwork, caps)
        } else {
            handleSuspended(
                callbackNetwork,
                "IMS network capabilities lost NOT_SUSPENDED caps=$caps",
            )
        }
    }

    private fun handleNotSuspended(currentNetwork: Network, caps: NetworkCapabilities) {
        if (actions.currentNetwork() != currentNetwork) {
            return
        }

        suspendedGeneration.incrementAndGet()
        if (readySuppressedByNetworkSuspension && !actions.isImsReady() && actions.isSocketInitialized()) {
            Rlog.w(tag, "Current IMS network recovered NOT_SUSPENDED; restoring IMS ready state: caps=$caps")
            readySuppressedByNetworkSuspension = false
            actions.markImsReady("IMS network recovered NOT_SUSPENDED")
        }
    }

    private fun handleSuspended(suspendedNetwork: Network, reason: String) {
        if (actions.currentNetwork() != suspendedNetwork) {
            Rlog.d(tag, "Ignoring suspension for non-current IMS network: $suspendedNetwork reason=$reason")
            return
        }

        val generation = suspendedGeneration.incrementAndGet()
        if (actions.hasActiveOrPendingCall()) {
            actions.setPendingReconnectAfterActiveCall("IMS network suspended during active/pending call: $reason")
            Rlog.w(
                tag,
                "Current IMS network suspended during active/pending call; deferring reconnect: " +
                    actions.activeOrPendingCallSummary(),
            )
            return
        }

        if (actions.isImsReady()) {
            Rlog.w(tag, "Keeping IMS registered during temporary current-network suspension grace: $reason")
        } else {
            Rlog.w(tag, "Current IMS network suspended while IMS is not ready: $reason")
        }

        handler.postDelayed({
            if (suspendedGeneration.get() != generation) {
                Rlog.d(tag, "IMS network suspension check superseded: generation=$generation")
                return@postDelayed
            }
            if (actions.currentNetwork() != suspendedNetwork) {
                Rlog.d(tag, "IMS network suspension check ignored; network changed: $suspendedNetwork")
                return@postDelayed
            }

            val currentCaps = try {
                connectivityManager.getNetworkCapabilities(suspendedNetwork)
            } catch (t: Throwable) {
                null
            }

            if (currentCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED) == true) {
                Rlog.d(tag, "IMS network recovered NOT_SUSPENDED during suspension grace: caps=$currentCaps")
                suspendedGeneration.incrementAndGet()
                if (readySuppressedByNetworkSuspension && !actions.isImsReady() && actions.isSocketInitialized()) {
                    readySuppressedByNetworkSuspension = false
                    actions.markImsReady("IMS network recovered NOT_SUSPENDED after suspension grace")
                }
                return@postDelayed
            }

            val dropReason = "IMS network suspended/stale before onLost: $reason caps=$currentCaps"
            Rlog.w(tag, "Dropping SIP state for suspended current IMS network: $dropReason")
            readySuppressedByNetworkSuspension = false
            actions.invalidatePendingReconnects("IMS network suspended")
            actions.dropImsConnection(dropReason)
            actions.setAbandonedBecauseOfNoPcscf()
            actions.scheduleImsNetworkRequestRestart(dropReason, 5_000L)
        }, SUSPENSION_GRACE_MS)
    }

    private companion object {
        const val SUSPENSION_GRACE_MS = 8_000L
    }
}
