//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.content.Context
import android.media.*
import android.net.*
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.telephony.Rlog
import android.telephony.TelephonyManager
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.*
import java.net.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class SipHandler(
    val ctxt: Context,
    private val slotId: Int,
    private val requestedSubId: Int,
) {
    companion object {
        private const val TAG = "PHH SipHandler"
        // Keep RTP receive() short. Android/libcore synchronizes DatagramSocket
        // send() and receive() on the same socket object; a long blocking receive
        // can therefore stall uplink RTP sends for the whole SO_TIMEOUT window.
        private const val RTP_SOCKET_RECEIVE_TIMEOUT_MS = 20

        private const val INCOMING_ACCEPT_IMS_ACCESS_CHANGE_GUARD_MS = 1_200L
    }

    val myHandler = Handler(HandlerThread("PhhMmTelFeature").apply { start() }.looper)
    val myExecutor = Executor { p0 -> myHandler.post(p0) }

    private val telephonyManager: TelephonyManager
    private val connectivityManager: ConnectivityManager
    private val ipSecManager: IpSecManager
    init {
        telephonyManager = ctxt.getSystemService(TelephonyManager::class.java)
        connectivityManager = ctxt.getSystemService(ConnectivityManager::class.java)
        ipSecManager = ctxt.getSystemService(IpSecManager::class.java)
    }


    private val amrWbMediaCodecAvailable: Boolean by lazy {
        SipAudioCodecNegotiator.isMediaCodecAvailableFor(TAG, SipAudioCodecs.AMR_WB)
    }

    private val imsUplinkGainQ8: Int by lazy {
        SipUplinkGain.configuredGainQ8()
    }


    private val subscriptionContext = SipSubscriptionContext.resolve(
        ctxt = ctxt,
        telephonyManager = telephonyManager,
        slotId = slotId,
        requestedSubId = requestedSubId,
    )
    private val activeSubscription = subscriptionContext.activeSubscription
    private val subId = subscriptionContext.subId
    private val subTelephonyManager = subscriptionContext.telephonyManager
    private val imei = subscriptionContext.imei

    private fun normalizeOutgoingDialTargetForTelUri(rawPhoneNumber: String): String =
        OutgoingDialTargetNormalizer.normalize(
            rawPhoneNumber = rawPhoneNumber,
            activeSubscription = activeSubscription,
            telephonyManager = subTelephonyManager,
            logTag = TAG,
        )

    private val wfcSubscriptionSettingMonitor = WfcSubscriptionSettingMonitor(
        tag = TAG,
        ctxt = ctxt,
        handler = myHandler,
        subId = subId,
        onWfcDisabled = { reason -> onWfcDisabled(reason) },
        onWfcPreferenceChanged = { reason, oldMode, newMode -> onWfcPreferenceChanged(reason, oldMode, newMode) },
        onAirplaneModeDisabled = { reason -> onAirplaneModeDisabled(reason) },
    ).also { it.start() }
    private val homeOperatorForIms = SipOperatorNumericResolver.resolveHomeOperatorForIms(
        telephonyManager = subTelephonyManager,
        activeSubscription = activeSubscription,
        slotId = slotId,
        subId = subId,
    )
    private val carrierSettings = SipCarrierSettings.fromSimOperator(homeOperatorForIms)
    private val mcc = carrierSettings.mcc
    private val mnc = carrierSettings.mnc
    private val imsi = subTelephonyManager.subscriberId

    // dual-SIM IMS context logging.
    // Keep ambiguous SIP/IMS events tied to the exact SipHandler subscription.
    private fun imsDualSimDebugContext(extra: String = ""): String {
        val networkText = if (this::network.isInitialized) network.toString() else "unassigned"
        val localText = if (this::localAddr.isInitialized) localAddr.hostAddress else "unassigned"
        val pcscfText = if (this::pcscfAddr.isInitialized) pcscfAddr.hostAddress else "unassigned"
        val ifaceText = try {
            if (this::network.isInitialized) {
                connectivityManager.getLinkProperties(network)?.interfaceName ?: "none"
            } else {
                "none"
            }
        } catch (_: Throwable) {
            "error"
        }
        val base =
            "slotId=$slotId phoneId=$slotId subId=$subId requestedSubId=$requestedSubId " +
                "sim=$mcc$mnc realm=$realm net=$networkText if=$ifaceText " +
                "local=$localText pcscf=$pcscfText"
        return if (extra.isBlank()) base else "$base $extra"
    }


    val isControlSocketUdp = carrierSettings.isControlSocketUdp
    val requireNonsessAka = carrierSettings.requireNonsessAka

    //private val realm = "ims.mnc$mnc.mcc$mcc.3gppnetwork.org"
    private val realm = "ims.mnc$mnc.mcc$mcc.3gppnetwork.org"
    private val user = "$imsi@$realm"
    private var registerTargetRealm = realm
    private var akaDigest = ""
    private var registerSecurityClientOverride: String? = null
    private var selectedSecurityClientForPromotedRegister: String? = null
    /*
     * Compatibility fallback for IMS cores where the AKA challenge realm
     * looks like an IMS registrar but must remain auth-only.
     *
     * Start with the existing challenged-realm promotion for carriers that
     * need it. If the protected REGISTER is rejected with 494 and the
     * canonical retry succeeds, keep the challenged realm auth-only for
     * later re-registration attempts handled by this SipHandler.
     */
    private var preferCanonicalRegisterRealmAfter494 = false
    private fun initialRegisterAuthorization(): String =
        """Digest username="$user",realm="$realm",nonce="",uri="sip:$realm",response="",algorithm=AKAv1-MD5"""
    fun generateCallId(): SipHeadersMap = SipCallIdGenerator.generate()

    /*
     * Write and flush a complete SIP frame to the socket writer.
     *
     * Keep logging the byte count and first line so carrier-specific transaction
     * failures can be correlated with the exact request/response sent.
     */
    private fun writeSipBytesWithFlush(
        writer: java.io.OutputStream,
        label: String,
        bytes: ByteArray,
    ) {
        val firstLine = bytes.toString(Charsets.US_ASCII).lineSequence().firstOrNull().orEmpty()
        synchronized(writer) {
            writer.write(bytes)
            writer.flush()
        }
        Rlog.d(TAG, "SIP write complete label=$label bytes=${bytes.size} firstLine=$firstLine")
    }


    private var registerCounter = 1
    private var registerHeaders =
        """
        From: <sip:$user>
        To: <sip:$user>
        """.toSipHeadersMap() + generateCallId()
    private var commonHeaders = "".toSipHeadersMap()
    private var contact = ""
    private var mySip = ""
    private var myTel = ""

    // too many lateinit, bad separation?
    lateinit private var localAddr: InetAddress
    lateinit private var pcscfAddr: InetAddress

    lateinit var ipsecSettings: SipIpsecSettings
    private var ipsecResourcesClosed = true

    lateinit private var network: Network

    lateinit private var plainSocket: SipConnection
    lateinit private var socket: SipConnection
    lateinit private var serverSocket: SipConnectionTcpServer
    lateinit private var serverSocketUdp: SipConnectionUdpServer
    private var reliableSequenceCounter = 67
    private val incomingFinalResponseSent = AtomicBoolean(false)
    private val incomingAcceptedAwaitingAck = AtomicBoolean(false)
    private val incomingHangupAfterAck = AtomicBoolean(false)
    private val terminatedIncomingCallIds = RecentCallIdCache(
        tag = TAG,
        label = "terminated incoming",
        ttlMs = 120_000L,
    )

    private fun rememberTerminatedIncomingCall(callId: String, reason: String) {
        terminatedIncomingCallIds.remember(callId, "duplicate INVITE guard: $reason")
    }

    private fun wasRecentlyTerminatedIncomingCall(callId: String): Boolean {
        return terminatedIncomingCallIds.contains(callId)
    }

    private val dispatcher = SipDispatcher(TAG)

    private val inviteSessionTimerPolicy = SipInviteSessionTimerPolicy(TAG)
    private val smsFallbackPolicy = SipSmsFallbackPolicy(TAG)
    /*
     * UDP SIP responses must be sent on the same 5-tuple that delivered the
     * request. A plain ByteArrayOutputStream only works for immediate responses
     * generated before the UDP receive loop returns; it breaks delayed dialog
     * responses such as incoming final 200 OK and can make peers retransmit
     * in-dialog UPDATE because the 200 OK was not delivered promptly.
     */
    private inner class UdpSipResponseWriter(
        private val remoteAddress: InetAddress,
        private val remotePort: Int,
    ) : OutputStream() {
        private val pendingSingleByteWrites = ByteArrayOutputStream()

        override fun write(b: Int) {
            pendingSingleByteWrites.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            flushPendingSingleByteWrites()
            val bytes = b.copyOfRange(off, off + len)
            sendDatagram(bytes)
        }

        override fun flush() {
            flushPendingSingleByteWrites()
        }

        private fun flushPendingSingleByteWrites() {
            val bytes = pendingSingleByteWrites.toByteArray()
            if (bytes.isEmpty()) return
            pendingSingleByteWrites.reset()
            sendDatagram(bytes)
        }

        private fun sendDatagram(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            val firstLine = bytes.toString(Charsets.US_ASCII)
                .lineSequence()
                .firstOrNull()
                .orEmpty()

            val channel = serverSocketUdp.socket.channel
            if (channel != null) {
                val sent = channel.send(
                    java.nio.ByteBuffer.wrap(bytes),
                    java.net.InetSocketAddress(remoteAddress, remotePort),
                )
                if (sent != bytes.size) {
                    Rlog.w(
                        TAG,
                        "UDP SIP response partial send bytes=$sent expected=${bytes.size} " +
                            "target=$remoteAddress:$remotePort firstLine=$firstLine",
                    )
                }
            } else {
                // Fallback for sockets not backed by a DatagramChannel. This can still
                // contend with receive(), but keeps the writer functional on all socket
                // construction paths.
                serverSocketUdp.socket.send(
                    DatagramPacket(bytes, bytes.size, remoteAddress, remotePort),
                )
            }

            Rlog.d(
                TAG,
                "UDP SIP response sent bytes=${bytes.size} " +
                    "target=$remoteAddress:$remotePort firstLine=$firstLine",
            )
        }
    }

    // SIP responses must be written back on the same transport flow that delivered the request.
    // This is especially important for incoming INVITE over the TCP server socket: writing the
    // 180/200 to the registration/control socket can make the P-CSCF ignore the final response.
    private val requestWriters = java.util.concurrent.ConcurrentHashMap<String, OutputStream>() 

    private val imsNetworkRequestRestarter = ImsNetworkRequestRestarter(
        tag = TAG,
        telephonyManager = subTelephonyManager,
        requestImsNetwork = { getVolteNetwork() },
    )
    private val reconnectController = ImsReconnectController(
        tag = TAG,
        currentNetwork = { if (this::network.isInitialized) network else null },
        setCurrentNetwork = { network = it },
        reportFailure = { imsFailureCallback?.invoke() },
        dropConnection = { reason -> dropImsConnection(reason) },
        connect = { connect() },
    )

    private val outgoingConnectedCallIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    private val outgoingConnectedDuplicateLogKeys = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    ) 
    private var imsReady = false
    var imsReadyCallback: (() -> Unit)? = null
    var imsFailureCallback: (() -> Unit)? = null
    var imsRegisteringCallback: ((Int) -> Unit)? = null
    private var imsRegistrationTech = REGISTRATION_TECH_LTE
    private var pendingCellularReconnectAfterWfcDisable = false
    private var pendingImsReconnectAfterActiveCallReason: String? = null
    @Volatile
    private var lastImsAccessChangeUptimeMs: Long = 0L
    @Volatile
    private var lastImsAccessChangeReason: String = ""
    
    private var imsNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val sipReaderGeneration = AtomicInteger(0)
    private val imsTransportGuard by lazy {
        ImsTransportGuard(
            TAG,
            myHandler,
            connectivityManager,
            object : ImsTransportGuard.Actions {
                override fun currentNetwork(): Network? =
                    if (this@SipHandler::network.isInitialized) network else null

                override fun isSocketInitialized(): Boolean = this@SipHandler::socket.isInitialized

                override fun isImsReady(): Boolean = imsReady

                override fun setImsReadyForTransportSuppression(ready: Boolean) {
                    imsReady = ready
                }

                override fun notifyImsFailure() {
                    imsFailureCallback?.invoke()
                }

                override fun markImsReady(reason: String) {
                    this@SipHandler.markImsReady(reason)
                }

                override fun hasActiveOrPendingCall(): Boolean =
                    hasActiveOrPendingCallForImsReconnectDeferral()

                override fun setPendingReconnectAfterActiveCall(reason: String) {
                    pendingImsReconnectAfterActiveCallReason = reason
                }

                override fun activeOrPendingCallSummary(): String =
                    activeOrPendingCallSummaryForReconnectDeferral()

                override fun invalidatePendingReconnects(reason: String) {
                    reconnectController.invalidatePendingReconnects(reason)
                }

                override fun dropImsConnection(reason: String) {
                    this@SipHandler.dropImsConnection(reason)
                }

                override fun setAbandonedBecauseOfNoPcscf() {
                    abandonnedBecauseOfNoPcscf = true
                }

                override fun scheduleImsNetworkRequestRestart(reason: String, delayMs: Long) {
                    this@SipHandler.scheduleImsNetworkRequestRestart(reason, delayMs)
                }
            },
        )
    }

private val smsHandler = SipSmsHandler(
        tag = TAG,
        ctxt = ctxt,
        subId = subId,
        realmProvider = { realm },
        commonHeadersProvider = { commonHeaders },
        mySipProvider = { mySip },
        writerProvider = { socket.gWriter() },
        responseCallbackSetter = { callId, cb -> setResponseCallback(callId, cb) },
        smsSipFailureListener = { smsRealm, statusCode -> smsFallbackPolicy.learnFromSipMessageFailure(smsRealm, statusCode) },
        timeoutScheduler = { delayMs, action -> myHandler.postDelayed({ action() }, delayMs) },
    )

    var onSmsReceived: ((Int, String, ByteArray) -> Unit)?
        get() = smsHandler.onSmsReceived
        set(value) {
            smsHandler.onSmsReceived = value
        }

    var onSmsStatusReportReceived: ((Int, String, ByteArray) -> Unit)?
        get() = smsHandler.onSmsStatusReportReceived
        set(value) {
            smsHandler.onSmsStatusReportReceived = value
        }
    var onIncomingCall: ((handle: Object, from: String, extras: Map<String, String>) -> Unit)? =
        null
    var onOutgoingCallConnected: ((handle: Object, extras: Map<String, String>) -> Unit)? =
        null
    var onOutgoingCallProgressing: ((handle: Object, extras: Map<String, String>) -> Unit)? =
        null
    var onIncomingCallConnected: ((handle: Object, extras: Map<String, String>) -> Unit)? =
        null
    var onCancelledCall: ((handle: Object, from: String, extras: Map<String, String>) -> Unit)? =
        null 

    
    private fun unregisterImsNetworkCallback(reason: String) {
        val callback = imsNetworkCallback ?: return

        try {
            connectivityManager.unregisterNetworkCallback(callback)
            Rlog.w(TAG, "Unregistered IMS NetworkCallback: $reason")
        } catch (t: Throwable) {
            Rlog.d(TAG, "Unregistering IMS NetworkCallback failed: $reason", t)
        }

        if (imsNetworkCallback === callback) {
            imsNetworkCallback = null
        }
    }

    private fun runDeferredImsReconnectAfterCallTerminalState(reason: String) {
        if (reason == "IMS reconnect") {
            return
        }

        val deferredReconnectReason = pendingImsReconnectAfterActiveCallReason ?: return
        pendingImsReconnectAfterActiveCallReason = null

        Rlog.w(
            TAG,
            "Scheduling deferred IMS reconnect after call terminal state: " +
                "$deferredReconnectReason terminalReason=$reason",
        )
        scheduleReconnectRetry(
            "deferred until call terminal state: $deferredReconnectReason",
            1000L,
        )
    }

    private fun stopCallRuntime(reason: String) {
        Rlog.d(TAG, "Stopping call runtime state: $reason")
        callStopped.set(true)
        callStarted.set(false)
        threadsStarted.set(false)

        SipAudioModeRestorer.restoreAfterImsCall(
            logTag = TAG,
            context = ctxt,
            reason = "stop runtime: $reason",
            previousMode = null,
        )
        runDeferredImsReconnectAfterCallTerminalState(reason)
    }

    private fun writeSipBytes(writer: OutputStream, bytes: ByteArray, label: String): Boolean {
        return SipMessageWriter.write(TAG, writer, bytes, label)
    }

    

fun setRequestCallback(method: SipMethod, cb: (SipRequest) -> Int) {
        dispatcher.setRequestCallback(method, cb)
    }

    fun setResponseCallback(callId: String, cb: (SipResponse) -> Boolean) {
        dispatcher.setResponseCallback(callId, cb)
    }

    fun parseMessage(reader: SipReader, writer: OutputStream): Boolean {
        return dispatcher.parseMessage(reader, writer)
    }

    private fun sipHeaderValues(response: SipResponse, name: String): List<String> {
        val lowerName = name.lowercase()
        return if (lowerName == name) {
            response.headers[name].orEmpty()
        } else {
            response.headers[lowerName].orEmpty() + response.headers[name].orEmpty()
        }
    }

    private fun isOutgoingInviteAuthFailure(response: SipResponse): Boolean {
        if (response.statusCode != 500) {
            return false
        }

        val cseq = sipHeaderValues(response, "cseq").joinToString(" ")
        val responseText = response.toString()
        val isInviteResponse =
            cseq.contains("INVITE", ignoreCase = true) ||
                responseText.contains("CSeq:", ignoreCase = true) &&
                responseText.contains("INVITE", ignoreCase = true)

        if (!isInviteResponse) {
            return false
        }

        val debugInfo = sipHeaderValues(response, "p-debug-info").joinToString(" ")
        val warning = sipHeaderValues(response, "warning").joinToString(" ")
        val combined = "$debugInfo $warning $responseText"

        return combined.contains("AUTH failure", ignoreCase = true) ||
            combined.contains("not authorised", ignoreCase = true) ||
            combined.contains("not authorized", ignoreCase = true)
    }

    fun handleResponse(response: SipResponse): Boolean {
        val keepCallback = dispatcher.handleResponse(response)

        if (isOutgoingInviteAuthFailure(response)) {
            Rlog.w(
                TAG,
                "Outgoing INVITE failed with SIP auth/security context error; " +
                    "scheduling IMS reconnect",
            )
            scheduleReconnectRetry("outgoing INVITE auth failure", 1000L)
        }

        return keepCallback
    }

    private val IWLAN_CONVERGENCE_OUTGOING_CALL_GUARD_MS = 60_000L
    private val WFC_WIFI_PREFERRED_IWLAN_READY_RETRY_MS = 1500L
    private val WFC_WIFI_PREFERRED_IWLAN_READY_TIMEOUT_MS = 20_000L


    private fun isWaitingForPreferredImsAccessAfterWfcPreferenceChange(): Boolean {
        val elapsedMs = SystemClock.uptimeMillis() - wfcSubscriptionSettingMonitor.lastChangeUptimeMs()
        return WfcImsAccessPolicy.isWaitingForRequiredAccessAfterWfcChange(
            convergenceWindow = elapsedMs in 0L..IWLAN_CONVERGENCE_OUTGOING_CALL_GUARD_MS,
            wifiOnly = wfcSubscriptionSettingMonitor.isWifiOnly(),
            wifiPreferredOrOnly = wfcSubscriptionSettingMonitor.isWifiPreferredOrOnly(),
            registeredOverLte = imsRegistrationTech == REGISTRATION_TECH_LTE,
            registeredOverIwlan = imsRegistrationTech == REGISTRATION_TECH_IWLAN,
        )
    }
    fun isReadyForOutgoingCall(): Boolean {
        val baseReady =
            imsReady &&
                !reconnectController.isReconnecting() &&
                this::network.isInitialized &&
                this::socket.isInitialized

        if (!baseReady) {
            Rlog.w(
                TAG,
                "Rejecting outgoing call while IMS is not stable: " +
                    "imsReady=$imsReady reconnecting=${reconnectController.isReconnecting()} " +
                    "networkInitialized=${this::network.isInitialized} " +
                    "socketInitialized=${this::socket.isInitialized}",
            )
            return false
        }

        val currentLocalAddr = if (this::localAddr.isInitialized) localAddr else null
        if (!imsTransportGuard.isUsableForOutgoingCall(currentLocalAddr, "outgoing call readiness")) {
            val staleReason = "outgoing call attempted while IMS transport is stale or suspended"
            Rlog.w(TAG, "Rejecting outgoing call while IMS transport is stale/suspended; forcing IMS reconnect")
            reconnectIms(staleReason)
            return false
        }

        if (isWaitingForPreferredImsAccessAfterWfcPreferenceChange()) {
            val elapsedMs = android.os.SystemClock.uptimeMillis() - wfcSubscriptionSettingMonitor.lastChangeUptimeMs()
            val preferredAccess =
                if (wfcSubscriptionSettingMonitor.isWifiOnly()) "IWLAN" else "cellular"
            Rlog.w(
                TAG,
                "Rejecting outgoing call while waiting for required IMS access after WFC preference/subscription change: " +
                    "preferred=$preferredAccess tech=${registrationTechName(imsRegistrationTech)} elapsedMs=$elapsedMs",
            )
            return false
        }

        return true
    }

    fun getRegistrationTech(): Int = imsRegistrationTech

    fun handlesSubscription(candidateSubId: Int): Boolean = subId == candidateSubId

    fun shouldForceCsfbForOutgoingDialString(number: String): Boolean {
        val normalizedNumber = normalizeOutgoingDialTargetForTelUri(number)
        val hasMmiControlChars =
            number.any { it == '*' || it == '#' } ||
                normalizedNumber.any { it == '*' || it == '#' }

        if (hasMmiControlChars) {
            Rlog.w(
                TAG,
                "Forcing CSFB for MMI/service-code dial target: " +
                    "raw=$number normalized=$normalizedNumber",
            )
            return true
        }

        val normalizedMnc = mnc.trimStart('0').ifBlank { "0" }
        val isDreiAt = mcc == "232" && normalizedMnc == "5"
        if (isDreiAt && normalizedNumber == "333") {
            Rlog.w(
                TAG,
                "Forcing CSFB for 3 AT service code that reached IMS stripped: " +
                    "raw=$number normalized=$normalizedNumber",
            )
            return true
        }
        return false
    }

    private fun markImsReady(reason: String) {
        if (imsReady) return
        Rlog.d(TAG, "IMS registration ready: $reason")
        imsReady = true
        reconnectController.markConnected()
        imsReadyCallback?.invoke()
    }

    private fun registrationTechName(tech: Int): String =
        ImsNetworkState.registrationTechName(tech)

    private fun detectRegistrationTech(lp: LinkProperties): Int {
        val currentNetwork = if (this::network.isInitialized) network else null
        return ImsNetworkState.detectRegistrationTech(connectivityManager, currentNetwork, lp)
    }

    private fun refreshRegistrationTechFromCurrentLink(reason: String) {
        val currentNetwork = if (this::network.isInitialized) network else null
        val lp = currentNetwork?.let { connectivityManager.getLinkProperties(it) } ?: return
        val newTech = detectRegistrationTech(lp)

        if (newTech == imsRegistrationTech) {
            Rlog.d(
                TAG,
                "IMS registration tech unchanged before $reason: " +
                    "${registrationTechName(newTech)} interface=${lp.interfaceName}",
            )
            return
        }

        Rlog.d(
            TAG,
            "IMS registration tech changed before $reason: " +
                "old=${registrationTechName(imsRegistrationTech)} " +
                "new=${registrationTechName(newTech)} interface=${lp.interfaceName}",
        )
        imsRegistrationTech = newTech
    }

    private fun resetRegistrationStateForConnect() {
        registerCounter = 1
        akaDigest = initialRegisterAuthorization()
        val registerCallId = generateCallId()
        val registerFromTag = registerCallId["call-id"]!!.first().take(12)
        registerHeaders =
            """
        From: <sip:$user>;tag=$registerFromTag
        To: <sip:$user>
        """.toSipHeadersMap() + registerCallId
        commonHeaders = "".toSipHeadersMap()
        registerTargetRealm = realm
        registerSecurityClientOverride = null
        selectedSecurityClientForPromotedRegister = null
        contact = ""
        mySip = ""
        myTel = ""
        imsReady = false
    }

    private fun getPcscfServers(lp: LinkProperties): List<InetAddress> =
        ImsNetworkState.getPcscfServers(lp)

    private fun getImsLocalAddress(lp: LinkProperties): InetAddress? =
        ImsNetworkState.getImsLocalAddress(lp)

    private fun clearCallAndCallbackStateForReconnect() {
        stopCallRuntime("IMS reconnect")
        incomingFinalResponseSent.set(false)
        incomingAcceptedAwaitingAck.set(false)
        incomingHangupAfterAck.set(false)
        terminatedIncomingCallIds.clear()
        currentCall = null
        clearPendingOutgoingInvite(closeRtpSocket = true, reason = "IMS reconnect")
        callGeneration.incrementAndGet()
        prAckWaitTracker.clearAndNotifyAll()
        dispatcher.clearCallbacks()
        dispatcher.clearWriters()
        smsHandler.clearState()
    }


    private fun closeSipTransports(reason: String) {
        Rlog.w(TAG, "Closing SIP transports: $reason")
        val newGeneration = sipReaderGeneration.incrementAndGet()
        Rlog.w(TAG, "Invalidated SIP reader generation=$newGeneration while closing transports: $reason")
        BoundedCloser.close(TAG, "plainSocket") { if (this::plainSocket.isInitialized) plainSocket.close() }
        BoundedCloser.close(TAG, "socket") { if (this::socket.isInitialized) socket.close() }
        BoundedCloser.close(TAG, "TCP server") { if (this::serverSocket.isInitialized) serverSocket.close() }
        BoundedCloser.close(TAG, "UDP server") { if (this::serverSocketUdp.isInitialized) serverSocketUdp.close() }
    }


    private fun connectSipSocketWithWatchdog(
        connection: SipConnection,
        remotePort: Int,
        label: String,
        timeoutMs: Long = 10_000L,
    ) {
        SipOperationWatchdog.connectSipSocket(
            logTag = TAG,
            connection = connection,
            remoteAddress = pcscfAddr,
            remotePort = remotePort,
            label = label,
            timeoutMs = timeoutMs,
        )
    }

    private fun allocateSecurityParameterIndexWithWatchdog(
        label: String,
        address: InetAddress,
        requestedSpi: Int? = null,
        timeoutMs: Long = 10_000L,
    ): IpSecManager.SecurityParameterIndex {
        return SipOperationWatchdog.allocateSecurityParameterIndex(
            logTag = TAG,
            ipSecManager = ipSecManager,
            label = label,
            address = address,
            requestedSpi = requestedSpi,
            timeoutMs = timeoutMs,
        )
    }

    private fun closeIpsecResources(reason: String) {
        if (!this::ipsecSettings.isInitialized || ipsecResourcesClosed) return
        val settings = ipsecSettings
        ipsecResourcesClosed = true
        Rlog.w(TAG, "Closing SIP IPsec resources: $reason")

        BoundedCloser.close(TAG, "serverInTransform") { settings.serverInTransform?.close() }
        BoundedCloser.close(TAG, "serverOutTransform") { settings.serverOutTransform?.close() }
        BoundedCloser.close(TAG, "serverSpiC") { settings.serverSpiC?.close() }
        BoundedCloser.close(TAG, "serverSpiS") { settings.serverSpiS?.close() }
        BoundedCloser.close(TAG, "clientSpiC") { settings.clientSpiC.close() }
        BoundedCloser.close(TAG, "clientSpiS") { settings.clientSpiS.close() }
    }
    private fun dropImsConnection(reason: String) {
        val wasReady = imsReady
        clearCallAndCallbackStateForReconnect()
        resetRegistrationStateForConnect()
        if (wasReady) {
            Rlog.w(TAG, "Reporting IMS deregistered before reconnect cleanup: $reason")
            imsFailureCallback?.invoke()
        }
        closeSipTransports(reason)
        closeIpsecResources(reason)
    }

    fun shutdown(reason: String, notifyFramework: Boolean = true) {
        myHandler.post {
            Rlog.w(
                TAG,
                "Shutting down SipHandler for slotId=$slotId subId=$subId: " +
                    "$reason notifyFramework=$notifyFramework"
            )

            if (!notifyFramework) {
                imsFailureCallback = null
            }

            reconnectController.invalidatePendingReconnects("SipHandler shutdown: $reason")
            pendingImsReconnectAfterActiveCallReason = null
            imsNetworkRequestRestarter.invalidate("SipHandler shutdown: $reason")
            dropImsConnection("SipHandler shutdown: $reason")
            unregisterImsNetworkCallback("SipHandler shutdown: $reason")
            wfcSubscriptionSettingMonitor.stop()

            imsReadyCallback = null
            imsFailureCallback = null
            imsRegisteringCallback = null
            onSmsReceived = null
            onSmsStatusReportReceived = null
            onIncomingCall = null
            onOutgoingCallConnected = null
            onIncomingCallConnected = null
            onCancelledCall = null

            myHandler.looper.quitSafely()
        }
    }

    private fun onAirplaneModeDisabled(reason: String) {
        myHandler.post {
            val currentTech = imsRegistrationTech
            if (wfcSubscriptionSettingMonitor.isWifiPreferredOrOnly()) {
                Rlog.d(
                    TAG,
                    "Keeping IWLAN IMS after airplane mode off because WFC still prefers Wi-Fi: " +
                        "$reason ready=$imsReady tech=${registrationTechName(currentTech)}",
                )
                return@post
            }

            if (!imsReady || currentTech != REGISTRATION_TECH_IWLAN) {
                Rlog.d(
                    TAG,
                    "Ignoring airplane-mode-off IMS refresh while not registered over IWLAN: " +
                        "$reason ready=$imsReady tech=${registrationTechName(currentTech)}",
                )
                return@post
            }

            val restartReason = "airplane mode disabled while registered over IWLAN and WFC prefers cellular: $reason"
            if (currentCall != null) {
                Rlog.w(
                    TAG,
                    "Deferring IMS network request restart for $restartReason because " +
                        activeOrPendingCallSummaryForReconnectDeferral(),
                )
                pendingImsReconnectAfterActiveCallReason = restartReason
                return@post
            }

            Rlog.w(TAG, "Restarting IMS network request after $restartReason")
            reconnectController.invalidatePendingReconnects(restartReason)
            unregisterImsNetworkCallback(restartReason)
            dropImsConnection(restartReason)
            abandonnedBecauseOfNoPcscf = true
            scheduleImsNetworkRequestRestart(restartReason, 250L)
        }
    }


    private fun isPsIwlanReadyForWfcPreferenceRestart(): Boolean {
        val serviceState = try {
            subTelephonyManager.serviceState
        } catch (t: Throwable) {
            Rlog.d(TAG, "Unable to read ServiceState while waiting for IWLAN IMS access", t)
            null
        }

        val iwlanRegistration = serviceState?.getNetworkRegistrationInfo(
            android.telephony.NetworkRegistrationInfo.DOMAIN_PS,
            android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
        )

        val iwlanReady =
            iwlanRegistration?.isNetworkRegistered == true &&
                iwlanRegistration.accessNetworkTechnology == TelephonyManager.NETWORK_TYPE_IWLAN

        Rlog.d(
            TAG,
            "WFC Wi-Fi preferred IWLAN readiness: ready=$iwlanReady " +
                "reg=${iwlanRegistration?.networkRegistrationState} " +
                "rat=${iwlanRegistration?.accessNetworkTechnology}",
        )
        return iwlanReady
    }

    private fun restartImsNetworkRequestAfterAccessPreferenceChange(restartReason: String) {
        if (currentCall != null) {
            Rlog.w(
                TAG,
                "Deferring IMS network request restart for $restartReason because " +
                    activeOrPendingCallSummaryForReconnectDeferral(),
            )
            pendingImsReconnectAfterActiveCallReason = restartReason
            return
        }

        Rlog.w(TAG, "Restarting IMS network request after $restartReason")
        reconnectController.invalidatePendingReconnects(restartReason)
        unregisterImsNetworkCallback(restartReason)
        dropImsConnection(restartReason)
        abandonnedBecauseOfNoPcscf = true
        scheduleImsNetworkRequestRestart(restartReason, 250L)
    }

    private fun restartWhenIwlanReadyAfterWfcWifiPreference(
        restartReason: String,
        startedUptimeMs: Long,
    ) {
        if (!wfcSubscriptionSettingMonitor.isWifiPreferredOrOnly()) {
            Rlog.d(TAG, "WFC preference is no longer Wi-Fi preferred while waiting for IWLAN: $restartReason")
            return
        }

        if (isPsIwlanReadyForWfcPreferenceRestart()) {
            restartImsNetworkRequestAfterAccessPreferenceChange(restartReason)
            return
        }

        val elapsedMs = SystemClock.uptimeMillis() - startedUptimeMs
        if (elapsedMs >= WFC_WIFI_PREFERRED_IWLAN_READY_TIMEOUT_MS) {
            Rlog.w(
                TAG,
                "IWLAN did not become ready after WFC Wi-Fi preference change within ${elapsedMs}ms; " +
                    "restarting IMS request anyway: $restartReason",
            )
            restartImsNetworkRequestAfterAccessPreferenceChange(restartReason)
            return
        }

        Rlog.d(
            TAG,
            "Delaying IMS network request restart until IWLAN is ready after WFC Wi-Fi preference: " +
                "elapsedMs=$elapsedMs reason=$restartReason",
        )
        myHandler.postDelayed({
            restartWhenIwlanReadyAfterWfcWifiPreference(restartReason, startedUptimeMs)
        }, WFC_WIFI_PREFERRED_IWLAN_READY_RETRY_MS)
    }

    private fun onWfcPreferenceChanged(reason: String, oldMode: Int?, newMode: Int?) {
        myHandler.post {
            val restartReason = "WFC preference changed: $reason"

            if (WfcImsAccessPolicy.shouldSkipReconnectForWifiModeOnlyChange(
                    oldMode = oldMode,
                    newMode = newMode,
                    imsReady = imsReady,
                    registeredOverIwlan = imsRegistrationTech == REGISTRATION_TECH_IWLAN,
                )
            ) {
                Rlog.d(
                    TAG,
                    "Skipping IMS network request restart for WFC Wi-Fi mode-only change " +
                        "while already registered on IWLAN: oldMode=$oldMode mode=$newMode reason=$reason",
                )
                return@post
            }

            if (wfcSubscriptionSettingMonitor.isWifiPreferredOrOnly()) {
                restartWhenIwlanReadyAfterWfcWifiPreference(
                    restartReason = restartReason,
                    startedUptimeMs = SystemClock.uptimeMillis(),
                )
                return@post
            }

            restartImsNetworkRequestAfterAccessPreferenceChange(restartReason)
        }
    }


fun onWfcDisabled(reason: String) {
        myHandler.post {
            if (pendingCellularReconnectAfterWfcDisable) {
                Rlog.w(
                    TAG,
                    "WFC disabled notification arrived while already waiting for cellular IMS link; " +
                        "forcing IMS access refresh anyway: $reason",
                )
            }

            val currentTech = imsRegistrationTech
            if (!imsReady || currentTech != REGISTRATION_TECH_IWLAN) {
                Rlog.d(
                    TAG,
                    "Ignoring WFC disabled notification while not registered over IWLAN: " +
                        "$reason ready=$imsReady tech=${registrationTechName(currentTech)}",
                )
                return@post
            }

            val dropReason = "WFC disabled while registered over IWLAN: $reason"
            Rlog.w(TAG, "Pre-dropping IWLAN IMS without immediate reconnect: $dropReason")
            pendingCellularReconnectAfterWfcDisable = true
            reconnectController.invalidatePendingReconnects(dropReason)
            dropImsConnection(dropReason)
            abandonnedBecauseOfNoPcscf = false
        }
    }


    
    private fun isRatReadyForImsNetworkRequest(): Boolean =
        ImsNetworkState.isRatReadyForImsNetworkRequest(TAG, subTelephonyManager)

    private fun scheduleImsNetworkRequestRestart(reason: String, initialDelayMs: Long = 12_000L) {
        imsNetworkRequestRestarter.schedule(reason, initialDelayMs)
    }


    private fun shouldReconnectAfterSipTransportLoss(reason: String): Boolean {
        if (pendingCellularReconnectAfterWfcDisable) {
            Rlog.w(
                TAG,
                "Suppressing IMS reconnect for $reason because WFC-disabled IWLAN pre-drop is waiting for cellular IMS",
            )
            return false
        }
        if (hasActiveOrPendingCallForImsReconnectDeferral()) {
            pendingImsReconnectAfterActiveCallReason = reason
            Rlog.w(
                TAG,
                "Deferring IMS reconnect for $reason while SIP call is active or pending: " +
                    activeOrPendingCallSummaryForReconnectDeferral(),
            )
            return false
        }

        if (reconnectController.isReconnecting()) {
            Rlog.w(TAG, "Suppressing IMS reconnect for $reason because a controlled IMS reconnect is already running")
            return false
        }
        if (!this::network.isInitialized) {
            Rlog.w(TAG, "Suppressing IMS reconnect for $reason because no IMS network is initialized")
            return false
        }
        val lp = try {
            connectivityManager.getLinkProperties(network)
        } catch (t: Throwable) {
            null
        }
        if (lp == null) {
            Rlog.w(TAG, "Suppressing IMS reconnect for $reason because current IMS network has no link properties")
            scheduleImsNetworkRequestRestart("SIP transport lost with stale IMS network: $reason")
            return false
        }
        return true
    }


private fun scheduleReconnectRetry(reason: String, delayMs: Long) {
        reconnectController.scheduleReconnectRetry(reason, delayMs)
    }


    private fun failConnectAndRetry(reason: String, baseDelayMs: Long = 5000L) {
        reconnectController.failConnectAndRetry(reason, baseDelayMs)
    }

    

    

    

    private fun reconnectIms(reason: String, newNetwork: Network? = null, delayMs: Long = 1000L) {
        reconnectController.reconnectIms(reason, newNetwork, delayMs)
    }


    private fun restartOutgoingMediaAfterDialogSdpCodecChange(
        oldCall: Call?,
        newCall: Call?,
        reason: String,
    ) {
        if (oldCall == null || newCall == null || !newCall.outgoing) {
            return
        }

        val codecChanged =
            oldCall.audioCodec.name != newCall.audioCodec.name ||
                oldCall.audioCodec.sampleRate != newCall.audioCodec.sampleRate ||
                oldCall.amrTrack != newCall.amrTrack ||
                oldCall.dtmfTrack != newCall.dtmfTrack

        if (!codecChanged) {
            return
        }

        val oldCallId = oldCall.callHeaders["call-id"]?.getOrNull(0)
        val newCallId = newCall.callHeaders["call-id"]?.getOrNull(0)
        if (oldCallId != newCallId) {
            Rlog.d(
                TAG,
                "Ignoring outgoing dialog SDP codec change across call-id boundary: " +
                    "reason=$reason oldCallId=$oldCallId newCallId=$newCallId",
            )
            return
        }

        if (!threadsStarted.get()) {
            Rlog.d(
                TAG,
                "Outgoing dialog SDP changed codec before media start: reason=$reason " +
                    "old=${oldCall.audioCodec.name}/${oldCall.audioCodec.sampleRate} pt=${oldCall.amrTrack} " +
                    "new=${newCall.audioCodec.name}/${newCall.audioCodec.sampleRate} pt=${newCall.amrTrack}",
            )
            return
        }

        val newGeneration = callGeneration.incrementAndGet()
        callStopped.set(true)
        callStarted.set(false)
        threadsStarted.set(false)

        Rlog.w(
            TAG,
            "Restarting outgoing media after dialog SDP codec change: reason=$reason " +
                "old=${oldCall.audioCodec.name}/${oldCall.audioCodec.sampleRate} pt=${oldCall.amrTrack}/${oldCall.dtmfTrack} " +
                "new=${newCall.audioCodec.name}/${newCall.audioCodec.sampleRate} pt=${newCall.amrTrack}/${newCall.dtmfTrack} " +
                "generation=$newGeneration",
        )

        myHandler.postDelayed({
            val active = currentCall
            val activeCallId = active?.callHeaders?.get("call-id")?.getOrNull(0)
            if (active == null || activeCallId != newCallId || !active.outgoing) {
                Rlog.w(
                    TAG,
                    "Skipping outgoing media restart after dialog SDP codec change; " +
                        "activeCallId=$activeCallId expectedCallId=$newCallId activeOutgoing=${active?.outgoing}",
                )
                return@postDelayed
            }

            callStopped.set(false)
            callStarted.set(false)
            if (threadsStarted.compareAndSet(false, true)) {
                Rlog.w(
                    TAG,
                    "Starting restarted outgoing media after dialog SDP codec change: " +
                        "codec=${active.audioCodec.name}/${active.audioCodec.sampleRate} " +
                        "pt=${active.amrTrack}/${active.dtmfTrack} generation=${callGeneration.get()}",
                )
                callDecodeThread()
                callEncodeThread(callSnapshot = active)
            } else {
                Rlog.w(TAG, "Outgoing media restart skipped; threads already restarted")
            }
        }, 150L)
    }

    private fun hasActiveOrPendingCallForImsReconnectDeferral(): Boolean {
        val hasDialogState = currentCall != null || pendingOutgoingInvite != null
        if (hasDialogState) {
            return true
        }

        val hasMediaRuntime = !callStopped.get() && (callStarted.get() || threadsStarted.get())
        return hasMediaRuntime
    }

    private fun activeOrPendingCallSummaryForReconnectDeferral(): String {
        val active = currentCall
        val activeCallId = active?.callHeaders?.get("call-id")?.getOrNull(0)
        val pendingCallId = pendingOutgoingInvite?.callId
        return "activeCallId=$activeCallId activeOutgoing=${active?.outgoing} " +
            "pendingOutgoingCallId=$pendingCallId callStarted=${callStarted.get()} " +
            "threadsStarted=${threadsStarted.get()} callStopped=${callStopped.get()} " +
            "generation=${callGeneration.get()}"
    }

    private fun hasPendingIncomingCallForAcceptGuard(): Boolean {
        val call = currentCall ?: return false
        return !call.outgoing && !callStarted.get()
    }

    private fun noteImsAccessChangeDuringPendingIncomingCall(reason: String) {
        if (!hasPendingIncomingCallForAcceptGuard()) {
            return
        }

        lastImsAccessChangeUptimeMs = SystemClock.uptimeMillis()
        lastImsAccessChangeReason = reason
        Rlog.w(
            TAG,
            "Observed IMS access change while incoming call is pending: " +
                "$reason " + activeOrPendingCallSummaryForReconnectDeferral(),
        )
    }

    private fun delayIncomingAcceptAfterRecentImsAccessChange(callId: String): Boolean {
        val elapsedMs = SystemClock.uptimeMillis() - lastImsAccessChangeUptimeMs
        val remainingMs = INCOMING_ACCEPT_IMS_ACCESS_CHANGE_GUARD_MS - elapsedMs
        if (remainingMs <= 0L) {
            return true
        }

        Rlog.w(
            TAG,
            "Delaying incoming final 200 OK while IMS access change settles: " +
                "callId=$callId delayMs=$remainingMs lastChange=$lastImsAccessChangeReason",
        )

        try {
            Thread.sleep(remainingMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }

        val call = currentCall
        val stillSameIncomingCall = call != null && !call.outgoing && call.callIdOrEmpty() == callId
        if (!stillSameIncomingCall) {
            Rlog.w(
                TAG,
                "Incoming call changed/cancelled while waiting for IMS access guard; " +
                    "not sending final 200 OK callId=$callId current=${call?.callIdOrEmpty()} outgoing=${call?.outgoing}",
            )
            return false
        }

        return true
    }


    var abandonnedBecauseOfNoPcscf = false
    @Synchronized

    private fun readPlainRegisterReply(plainSocket: SipConnection): SipMessage? {
        return if (plainSocket is SipConnectionTcp) {
            plainSocket.gReader().parseMessage()
        } else {
            // In some IMS servers, in UDP send mode, message might come back to plainSocket or to serverSocketUdp
            if (select(listOf(serverSocketUdp.getChannel(), plainSocket.getChannel())) == 0)
                serverSocketUdp.gReader().parseMessage()
            else
                plainSocket.gReader().parseMessage()
        }
    }


    private fun handleAuthenticatedRegisterSuccess(regReply: SipResponse) {
        reconnectController.markConnected()

        installSipCallbacks()
        handleResponse(regReply)

        startSipReaderLoops()
    }


    private fun readRegisterReplyOrRetry(
        readFailureLog: String,
        readFailureReason: String,
        noResponseLog: String,
        noResponseReason: String,
        readReply: () -> SipMessage?,
    ): SipMessage? {
        val reply = try {
            readReply()
        } catch (t: Throwable) {
            Rlog.w(TAG, readFailureLog, t)
            failConnectAndRetry(readFailureReason)
            return null
        }

        if (reply == null) {
            Rlog.w(TAG, noResponseLog)
            failConnectAndRetry(noResponseReason)
            return null
        }

        return reply
    }


    private fun prepareImsEndpointForConnect(): Boolean {
        abandonnedBecauseOfNoPcscf = false
        resetRegistrationStateForConnect()
        Rlog.d(TAG, "Trying to connect to SIP server ${imsDualSimDebugContext()}")
        val lp = connectivityManager.getLinkProperties(network)
        Rlog.d(TAG, "Got link properties $lp")
        if (lp == null) {
            Rlog.w(TAG, "No link properties for IMS network")
            imsFailureCallback?.invoke()
            scheduleImsNetworkRequestRestart("No link properties for current IMS network", 1_000L)
            return false
        }
        imsRegistrationTech = detectRegistrationTech(lp)
        Rlog.d(TAG, "IMS registration tech ${registrationTechName(imsRegistrationTech)} interface=${lp.interfaceName} caps=${connectivityManager.getNetworkCapabilities(network)}")
        warnIfCurrentCellularImsEndpointDoesNotMatchWfcPolicy(lp)
        imsRegisteringCallback?.invoke(imsRegistrationTech)
        when (val endpoint = ImsNetworkState.resolveEndpoint(TAG, lp, mnc, mcc)) {
            is ImsNetworkEndpointResolution.Success -> {
                localAddr = endpoint.localAddr
                pcscfAddr = endpoint.pcscfAddr
            }

            ImsNetworkEndpointResolution.WaitingForPcscf -> {
                abandonnedBecauseOfNoPcscf = true
                return false
            }

            ImsNetworkEndpointResolution.NoLocalAddress -> {
                failConnectAndRetry("No usable local address on IMS link properties")
                return false
            }
        }

        return true
    }


    private fun setupPlainSipSocketsAndSendInitialRegister() {
        plainSocket = if (isControlSocketUdp)
            SipConnectionUdp(network, pcscfAddr, localAddr)
        else
            SipConnectionTcp(network, pcscfAddr, localAddr)
        connectSipSocketWithWatchdog(plainSocket, 5060, "plain initial")
        socket = if (plainSocket is SipConnectionTcp)
                SipConnectionTcp(network, pcscfAddr, plainSocket.gLocalAddr())
            else
                SipConnectionUdp(network, pcscfAddr, plainSocket.gLocalAddr())
        serverSocket =
            SipConnectionTcpServer(network, pcscfAddr, plainSocket.gLocalAddr(), socket.gLocalPort() + 1)
        serverSocketUdp =
            SipConnectionUdpServer(network, pcscfAddr, plainSocket.gLocalAddr(), socket.gLocalPort() + 1)

        Rlog.d(TAG, "SIP ports ${imsDualSimDebugContext("src=${socket.gLocalPort()} tcpServer=${serverSocket.localPort} udpServer=${serverSocketUdp.localPort}")}")
        updateCommonHeaders(plainSocket)
        register(plainSocket.gWriter())
    }


    private fun requirePlainRegisterChallengeResponse(plainRegReply: SipMessage?): SipResponse? {
        Rlog.d(TAG, "Received $plainRegReply")

        if (plainRegReply !is SipResponse || plainRegReply.statusCode != 401) {
            Rlog.w(TAG, "Didn't get expected response from initial register, aborting")
            plainSocket.close()
            failConnectAndRetry("Initial SIP REGISTER did not return 401")
            return null
        }

        return plainRegReply
    }


    private fun applyRegisterRealmDecision(challengeRealm: String) =
        SipRegisterNegotiationPolicy.registerRealmDecision(
            defaultRealm = realm,
            challengeRealm = challengeRealm,
            preferCanonicalAfterPromoted494 = preferCanonicalRegisterRealmAfter494,
        ).also { registerRealmDecision ->
            val registerDigestUriRealm = registerRealmDecision.targetRealm
            if (registerRealmDecision.forcedCanonical && registerRealmDecision.hasPromotedCandidate) {
                Rlog.w(
                    TAG,
                    "Keeping challenged REGISTER realm auth-only after previous promoted 494 success: " +
                        "oldUri=sip:$realm promotedUri=sip:${registerRealmDecision.candidateRealm} " +
                        "challengeRealm=$challengeRealm",
                )
            } else if (registerRealmDecision.usesPromotedChallengeRealm) {
                Rlog.w(
                    TAG,
                    "Using challenged REGISTER realm as request/digest URI: " +
                        "oldUri=sip:$realm newUri=sip:$registerDigestUriRealm " +
                        "challengeRealm=$challengeRealm",
                )
            }
            registerTargetRealm = registerDigestUriRealm
            registerSecurityClientOverride =
                if (registerTargetRealm != realm) {
                    selectedSecurityClientForPromotedRegister?.also {
                        Rlog.w(
                            TAG,
                            "Applying selected Security-Client for promoted REGISTER target: " +
                                "defaultRealm=$realm targetRealm=$registerTargetRealm securityClient=$it",
                        )
                    }
                } else {
                    null
                }
        }


    private fun readAuthenticatedRegisterReply(): SipMessage? {
        return if (socket is SipConnectionTcp) {
            socket.gReader().parseMessage()
        } else if (socket is SipConnectionUdp) {
            serverSocketUdp.gReader().parseMessage()
        } else {
            socket.gReader().parseMessage()
        }
    }


    private fun connectProtectedSipSocketAndRegister(portS: Int) {
        connectSipSocketWithWatchdog(socket, portS, "IPsec authenticated")
        updateCommonHeaders(socket)
        register()
    }


    private fun setupSecurityServerIpsecIfNeeded(
        plainRegReply: SipResponse,
        clientSpiS: IpSecManager.SecurityParameterIndex,
        clientSpiC: IpSecManager.SecurityParameterIndex,
        akaResult: SipAkaResult,
    ): Int {
        var portS = 5060
        // Check if there is a security-server header in the reply
        if (plainRegReply.headers.containsKey("security-server")) {
            val securityServer = plainRegReply.headers["security-server"]!!
            commonHeaders += ("security-verify" to securityServer)
            registerHeaders += ("security-verify" to securityServer)
            val securityServerParams = SipSecurityServerSelector.select(securityServer).params
                selectedSecurityClientForPromotedRegister =
                    SipRegisterNegotiationPolicy.selectedSecurityClientHeader(
                        securityServerParams = securityServerParams,
                        ipsecSettings = ipsecSettings,
                        clientPort = socket.gLocalPort(),
                        serverPort = serverSocket.localPort,
                    )


            // Keep the protected REGISTER Security-Client identical to the initial
            // Security-Client offer. Some IMS cores reject a narrowed/selected
            // Security-Client as a bid-down attack.
            registerSecurityClientOverride = null

            portS = securityServerParams["port-s"]!!.toInt()
            // spi string is 32 bit unsigned, but ipSecManager wants an int...
            val spiS = securityServerParams["spi-s"]!!.toUInt().toInt()
            val serverSpiS = allocateSecurityParameterIndexWithWatchdog("server SPI-S", pcscfAddr, spiS)

            val spiC = securityServerParams["spi-c"]!!.toUInt().toInt()
            val serverSpiC = allocateSecurityParameterIndexWithWatchdog("server SPI-C", pcscfAddr, spiC)

            ipsecSettings = SipIpsecSettings(
                clientSpiS = clientSpiS,
                clientSpiC = clientSpiC,
                serverSpiC = serverSpiC,
                serverSpiS = serverSpiS)
            ipsecResourcesClosed = false

            val ipsecTransforms = SipIpsecTransformBuilder.build(
                ctxt = ctxt,
                pcscfAddr = pcscfAddr,
                localAddr = localAddr,
                clientSpiS = clientSpiS,
                serverSpiC = serverSpiC,
                securityServerParams = securityServerParams,
                integrityKey = akaResult.ik,
                cipherKey = akaResult.ck,
            )
            val ipSecBuilder = ipsecTransforms.builder
            val serverInTransform = ipsecTransforms.serverInTransform
            val serverOutTransform = ipsecTransforms.serverOutTransform
            ipsecSettings = SipIpsecSettings(
                clientSpiS = clientSpiS,
                clientSpiC = clientSpiC,
                serverSpiC = serverSpiC,
                serverSpiS = serverSpiS,
                serverInTransform = serverInTransform,
                serverOutTransform = serverOutTransform)
            ipsecResourcesClosed = false
            socket.enableIpsec(ipSecBuilder, ipSecManager, clientSpiC, serverSpiS)
            serverSocket.enableIpsec(ipSecManager, serverInTransform, serverOutTransform)
            serverSocketUdp.enableIpsec(ipSecManager, serverInTransform, serverOutTransform)
        }

        return portS
    }


    private data class SipAkaRegisterChallengeResult(
        val plainRegReply: SipResponse,
        val registerChallenge: SipRegisterChallenge,
        val akaResult: SipAkaResult,
    )

    private fun resolveAkaRegisterChallenge(
        plainRegReply: SipResponse,
        registerChallenge: SipRegisterChallenge,
    ): SipAkaRegisterChallengeResult? {
        Rlog.d(TAG, "Requesting AKA challenge")
        val akaResult = when (val result = sipAkaChallengeForRegistration(subTelephonyManager, registerChallenge.nonceB64)) {
            is SipAkaChallengeResult.Success -> result.akaResult
            is SipAkaChallengeResult.SynchronizationFailure -> {
                Rlog.w(TAG, "AKA AUTS synchronization failure; sending one resynchronization REGISTER")
                akaDigest = SipRegistrationDigestFactory.createSynchronizationFailure(
                    user = user,
                    realm = registerChallenge.realm,
                    uri = "sip:$realm",
                    nonceB64 = registerChallenge.nonceB64,
                    opaque = registerChallenge.opaque,
                    auts = result.auts,
                    useNonsessAka = requireNonsessAka || registerChallenge.qop == null,
                )
                register(plainSocket.gWriter())

                val resyncReply = readPlainRegisterReply(plainSocket)
                Rlog.d(TAG, "Received after AKA AUTS resynchronization $resyncReply")
                if (resyncReply !is SipResponse || resyncReply.statusCode != 401) {
                    Rlog.w(TAG, "Didn't get expected 401 after AKA AUTS resynchronization, aborting")
                    plainSocket.close()
                    failConnectAndRetry("AKA AUTS resynchronization REGISTER did not return fresh 401")
                    return null
                }

                val resyncChallenge = SipRegisterChallengeParser.parse(
                    response = resyncReply,
                    fallbackRealm = realm,
                )
                Rlog.d(TAG, "Requesting AKA challenge after AUTS resynchronization")
                val resyncAkaResult = when (val retryResult = sipAkaChallengeForRegistration(subTelephonyManager, resyncChallenge.nonceB64)) {
                    is SipAkaChallengeResult.Success -> retryResult.akaResult
                    is SipAkaChallengeResult.SynchronizationFailure -> {
                        Rlog.w(TAG, "AKA still returns AUTS after one resynchronization REGISTER; aborting")
                        plainSocket.close()
                        failConnectAndRetry("AKA still out of sync after AUTS resynchronization")
                        return null
                    }
                }

                return SipAkaRegisterChallengeResult(
                    plainRegReply = resyncReply,
                    registerChallenge = resyncChallenge,
                    akaResult = resyncAkaResult,
                )
            }
        }

        return SipAkaRegisterChallengeResult(
            plainRegReply = plainRegReply,
            registerChallenge = registerChallenge,
            akaResult = akaResult,
        )
    }


    private fun retryCanonicalRegisterAfterPromotedRealm494(
        registerChallenge: SipRegisterChallenge,
        akaResult: SipAkaResult,
    ) {
        Rlog.w(
            TAG,
            "Promoted REGISTER realm rejected with 494; retrying once with canonical realm: " +
                "promotedUri=sip:$registerTargetRealm canonicalUri=sip:$realm " +
                "challengeRealm=${registerChallenge.realm}",
        )

        registerTargetRealm = realm
        registerSecurityClientOverride = null
        akaDigest = SipRegistrationDigestFactory.create(
            user = user,
            realm = registerChallenge.realm,
            uri = "sip:$realm",
            nonceB64 = registerChallenge.nonceB64,
            opaque = registerChallenge.opaque,
            akaResult = akaResult,
            useNonsessAka = requireNonsessAka || registerChallenge.qop == null,
        )
        register()

        Rlog.d(TAG, "Waiting for canonical REGISTER realm retry response")
        val canonicalRegReply = readRegisterReplyOrRetry(
            readFailureLog = "Canonical REGISTER realm retry response read failed, aborting SIP",
            readFailureReason = "Canonical REGISTER realm retry response read failed",
            noResponseLog = "Canonical REGISTER realm retry got EOF/no response, aborting SIP",
            noResponseReason = "Canonical REGISTER realm retry got EOF/no response",
            readReply = { readAuthenticatedRegisterReply() },
        ) ?: return

        Rlog.d(TAG, "Received after canonical REGISTER realm retry $canonicalRegReply")
        if (canonicalRegReply is SipResponse && canonicalRegReply.statusCode == 200) {
            preferCanonicalRegisterRealmAfter494 = true
            Rlog.w(
                TAG,
                "Canonical REGISTER realm retry accepted; keeping challenged realms auth-only " +
                    "for this IMS session",
            )

            handleAuthenticatedRegisterSuccess(canonicalRegReply)
            return
        }

        Rlog.w(TAG, "Canonical REGISTER realm retry did not return 200, aborting SIP")
        failConnectAndRetry("Canonical REGISTER realm retry did not return 200")
    }


    private fun allocateClientIpsecSettingsForRegister(): SipIpsecSettings {
        val clientSpiC = allocateSecurityParameterIndexWithWatchdog("client SPI-C", localAddr)
        val clientSpiS = allocateSecurityParameterIndexWithWatchdog("client SPI-S", localAddr, clientSpiC.spi + 1)

        return SipIpsecSettings(
            clientSpiS = clientSpiS,
            clientSpiC = clientSpiC,
        )
    }


    private fun handleAuthenticatedRegisterFailure(
        regReply: SipMessage,
        registerRealmDecision: SipRegisterNegotiationPolicy.RegisterRealmDecision,
        registerChallenge: SipRegisterChallenge,
        akaResult: SipAkaResult,
    ) {
        if (
            SipRegisterNegotiationPolicy.shouldRetryCanonicalAfterPromoted494(
                statusCode = (regReply as? SipResponse)?.statusCode,
                decision = registerRealmDecision,
                alreadyPreferCanonical = preferCanonicalRegisterRealmAfter494,
            )
        ) {
            retryCanonicalRegisterAfterPromotedRealm494(
                registerChallenge = registerChallenge,
                akaResult = akaResult,
            )
            return
        }

        Rlog.w(TAG, "Could not connect, aborting SIP")
        failConnectAndRetry("Authenticated SIP REGISTER did not return 200")
    }


    private fun readAndHandleAuthenticatedRegisterResponse(
        registerRealmDecision: SipRegisterNegotiationPolicy.RegisterRealmDecision,
        registerChallenge: SipRegisterChallenge,
        akaResult: SipAkaResult,
    ) {
        Rlog.d(TAG, "Waiting for authenticated SIP REGISTER response")
        val regReply = readRegisterReplyOrRetry(
            readFailureLog = "Authenticated SIP REGISTER response read failed, aborting SIP",
            readFailureReason = "Authenticated SIP REGISTER response read failed",
            noResponseLog = "Authenticated SIP REGISTER got EOF/no response, aborting SIP",
            noResponseReason = "Authenticated SIP REGISTER got EOF/no response",
            readReply = { readAuthenticatedRegisterReply() },
        ) ?: return
        Rlog.d(TAG, "Received $regReply")

        if (regReply !is SipResponse || regReply.statusCode != 200) {
            handleAuthenticatedRegisterFailure(
                regReply = regReply,
                registerRealmDecision = registerRealmDecision,
                registerChallenge = registerChallenge,
                akaResult = akaResult,
            )
            return
        }

        handleAuthenticatedRegisterSuccess(regReply)
    }


    private fun prepareAuthenticatedRegisterDigest(
        registerChallenge: SipRegisterChallenge,
        registerDigestUriRealm: String,
        akaResult: SipAkaResult,
    ) {
        akaDigest = SipRegistrationDigestFactory.create(
            user = user,
            realm = registerChallenge.realm,
            uri = "sip:$registerDigestUriRealm",
            nonceB64 = registerChallenge.nonceB64,
            opaque = registerChallenge.opaque,
            akaResult = akaResult,
            useNonsessAka = requireNonsessAka || registerChallenge.qop == null,
        )
    }


    private data class PlainRegisterChallengeResult(
        val plainRegReply: SipResponse,
        val registerChallenge: SipRegisterChallenge,
    )

    private fun readPlainRegisterChallenge(): PlainRegisterChallengeResult? {
        val plainRegReply = requirePlainRegisterChallengeResponse(
            readPlainRegisterReply(plainSocket),
        ) ?: return null

        val registerChallenge = SipRegisterChallengeParser.parse(
            response = plainRegReply,
            fallbackRealm = realm,
        )

        return PlainRegisterChallengeResult(
            plainRegReply = plainRegReply,
            registerChallenge = registerChallenge,
        )
    }


    private fun authenticateRegisterFromPlainChallenge(
        clientSpiS: IpSecManager.SecurityParameterIndex,
        clientSpiC: IpSecManager.SecurityParameterIndex,
    ) {
        val plainRegisterChallenge = readPlainRegisterChallenge() ?: return
        var plainRegReply = plainRegisterChallenge.plainRegReply
        var registerChallenge = plainRegisterChallenge.registerChallenge
        val akaChallengeResult = resolveAkaRegisterChallenge(
            plainRegReply = plainRegReply,
            registerChallenge = registerChallenge,
        ) ?: return
        plainRegReply = akaChallengeResult.plainRegReply
        registerChallenge = akaChallengeResult.registerChallenge
        val akaResult = akaChallengeResult.akaResult

        plainSocket.close()

        val registerRealmDecision = applyRegisterRealmDecision(registerChallenge.realm)
        val registerDigestUriRealm = registerRealmDecision.targetRealm
        prepareAuthenticatedRegisterDigest(
            registerChallenge = registerChallenge,
            registerDigestUriRealm = registerDigestUriRealm,
            akaResult = akaResult,
        )

        val portS = setupSecurityServerIpsecIfNeeded(
            plainRegReply = plainRegReply,
            clientSpiS = clientSpiS,
            clientSpiC = clientSpiC,
            akaResult = akaResult,
        )
        connectProtectedSipSocketAndRegister(portS)

        readAndHandleAuthenticatedRegisterResponse(
            registerRealmDecision = registerRealmDecision,
            registerChallenge = registerChallenge,
            akaResult = akaResult,
        )
    }


    private fun prepareClientIpsecSettingsForRegister(): SipIpsecSettings {
        val clientIpsecSettings = allocateClientIpsecSettingsForRegister()
        ipsecSettings = clientIpsecSettings
        ipsecResourcesClosed = false
        return clientIpsecSettings
    }


    private fun connectToPreparedImsEndpoint() {
        Rlog.w(TAG, "Connecting with address ${imsDualSimDebugContext("selectedLocal=$localAddr selectedPcscf=$pcscfAddr")}")

        val clientIpsecSettings = prepareClientIpsecSettingsForRegister()

        setupPlainSipSocketsAndSendInitialRegister()

        authenticateRegisterFromPlainChallenge(
            clientSpiS = clientIpsecSettings.clientSpiS,
            clientSpiC = clientIpsecSettings.clientSpiC,
        )
    }

    fun connect() {
        try {
            connectInnerAfterX1sImsLossHardening()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t

            val retryReason = "connect/register failed: ${t.javaClass.simpleName}"
            Rlog.e(TAG, "IMS connect/register attempt failed; dropping stale SIP state and retrying IMS network request", t)
            try {
                dropImsConnection(retryReason)
            } catch (cleanup: Throwable) {
                Rlog.w(TAG, "Failed to drop stale SIP state after $retryReason", cleanup)
            }
            abandonnedBecauseOfNoPcscf = true
            imsFailureCallback?.invoke()
            scheduleImsNetworkRequestRestart(retryReason, 1_000L)
        }
    }

    private fun connectInnerAfterX1sImsLossHardening() {
        if (!prepareImsEndpointForConnect()) {
            return
        }

        connectToPreparedImsEndpoint()
    }

    private fun startSipReaderLoops() {
        // Two ways we'll get incoming messages:
        // - reply to normal socket (just read forever)
        // - connection to server socket
        // Start both in threads as we're only called here from network callback from which
        // it's better to return.
        val readerGeneration = sipReaderGeneration.incrementAndGet()
        Rlog.d(TAG, "Starting SIP reader loops generation=$readerGeneration")

        startMainSipReaderLoop(readerGeneration)
        startTcpServerSipReaderLoop(readerGeneration)
        startUdpServerSipReaderLoop(readerGeneration)
    }

    private fun isStaleSipReaderLoop(readerGeneration: Int, reason: String): Boolean {
        val currentGeneration = sipReaderGeneration.get()
        if (readerGeneration == currentGeneration) {
            return false
        }

        Rlog.w(
            TAG,
            "Ignoring stale SIP reader event generation=$readerGeneration " +
                "currentGeneration=$currentGeneration: $reason",
        )
        return true
    }

    private fun startMainSipReaderLoop(readerGeneration: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                while (parseMessage(socket.gReader(), socket.gWriter())) {
                }
                Rlog.w(TAG, "Main socket got EOF, reconnecting")
            } catch (t: Throwable) {
                Rlog.w(TAG, "Got exception in main/control socket, reconnecting", t)
            }

            if (isStaleSipReaderLoop(readerGeneration, "main/control SIP socket lost")) {
                return@launch
            }

            if (shouldReconnectAfterSipTransportLoss("main/control SIP socket lost")) {
                reconnectIms("main/control SIP socket lost")
            }
        }
    }

    private fun startTcpServerSipReaderLoop(readerGeneration: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                while (true) {
                    val accepted = serverSocket.accept()
                    try {
                        while (parseMessage(accepted.reader, accepted.writer)) {
                        }
                    } catch (t: Throwable) {
                        if (serverSocket.serverSocket.isClosed) {
                            throw t
                        }
                        Rlog.w(TAG, "Got exception in accepted TCP server SIP flow; keeping IMS server socket alive", t)
                    } finally {
                        serverSocket.closeAccepted(accepted.socket)
                    }
                }
            } catch (t: Throwable) {
                Rlog.w(TAG, "Got exception in TCP server socket, reconnecting", t)

                if (isStaleSipReaderLoop(readerGeneration, "TCP server SIP socket lost")) {
                    return@launch
                }

                if (shouldReconnectAfterSipTransportLoss("TCP server SIP socket lost")) {
                    reconnectIms("TCP server SIP socket lost")
                }
            }
        }
    }

    private fun startUdpServerSipReaderLoop(readerGeneration: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bufferIn = ByteArray(128 * 1024)
                val dgramPacketIn = DatagramPacket(bufferIn, bufferIn.size)
                while (true) {
                    dgramPacketIn.length = bufferIn.size
                    serverSocketUdp.socket.receive(dgramPacketIn)
                    Rlog.d(TAG, "Received dgram packet")

                    val baIs = ByteArrayInputStream(dgramPacketIn.data, dgramPacketIn.offset, dgramPacketIn.length)
                    val reader = baIs.sipReader()
                    val writer = UdpSipResponseWriter(dgramPacketIn.address, dgramPacketIn.port)
                    while (parseMessage(reader, writer)) {
                    }

                    writer.flush()
                }
            } catch (t: Throwable) {
                if (isStaleSipReaderLoop(readerGeneration, "UDP server SIP socket lost")) {
                    return@launch
                }

                Rlog.d(TAG, "Got exception in UDP server socket", t)
            }
        }
    }

    private fun installSipCallbacks() {
        setResponseCallback(registerHeaders["call-id"]!![0], ::registerCallback)
        setRequestCallback(SipMethod.MESSAGE, ::handleSms)
        setRequestCallback(SipMethod.INVITE, ::handleCall)
        setRequestCallback(SipMethod.PRACK, ::handlePrack)
        setRequestCallback(SipMethod.ACK, ::handleAck)
        setRequestCallback(SipMethod.CANCEL, ::handleCancel)
        setRequestCallback(SipMethod.BYE, ::handleCancel)
        setRequestCallback(SipMethod.UPDATE, ::handleUpdate)
    }


    private fun handleImsNetworkLost(
        callback: ConnectivityManager.NetworkCallback,
        lostNetwork: Network,
    ) {
        Rlog.d(TAG, "IMS network lost ${imsDualSimDebugContext("lost=$lostNetwork")}")
        if (this::network.isInitialized && network == lostNetwork) {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
                if (imsNetworkCallback === callback) {
                    imsNetworkCallback = null
                }
                Rlog.w(TAG, "Unregistered stale IMS NetworkCallback after loss to avoid immediate GERAN IMS APN retry")
            } catch (t: Throwable) {
                Rlog.d(TAG, "Unregistering stale IMS NetworkCallback failed", t)
            }
            Rlog.w(TAG, "Current IMS network was lost; dropping SIP state")
            Rlog.w(TAG, "Invalidating IMS reconnect generation: current IMS network lost")
            reconnectController.invalidatePendingReconnects("IMS network state changed")
            dropImsConnection("IMS network lost")
            abandonnedBecauseOfNoPcscf = true
            imsFailureCallback?.invoke()
            scheduleImsNetworkRequestRestart("IMS network lost $lostNetwork", 1_000L)
        }
    }


    private fun handleImsNetworkCapabilitiesChanged(
        changedNetwork: Network,
        networkCapabilities: NetworkCapabilities,
    ) {
        Rlog.d(TAG, "IMS network capabilities changed ${imsDualSimDebugContext("capabilities=$networkCapabilities")}")
        val isCurrentImsNetwork =
            this::network.isInitialized &&
                changedNetwork == network

        if (isCurrentImsNetwork) {
            imsTransportGuard.onCapabilitiesChanged(changedNetwork, networkCapabilities)
        }

        if (
            isCurrentImsNetwork &&
                hasPendingIncomingCallForAcceptGuard()
        ) {
            noteImsAccessChangeDuringPendingIncomingCall(
                "IMS network capabilities changed caps=$networkCapabilities",
            )
        }
    }


    private fun handleImsNetworkAvailable(availableNetwork: Network) {
        Rlog.d(TAG, "Got IMS network ${imsDualSimDebugContext("network=$availableNetwork")}")
        if (!this::network.isInitialized) {
            network = availableNetwork
            thread {
                Thread.sleep(4000)
                try {
                    connect()
                } catch (e: Throwable) {
                    Rlog.e(TAG, "connect() failed from IMS network callback", e)
                    failConnectAndRetry("connect() failed from IMS network callback")
                }
            }
        } else if (abandonnedBecauseOfNoPcscf || network != availableNetwork) {
            reconnectIms(
                "new IMS network available old=${network} new=$availableNetwork abandoned=$abandonnedBecauseOfNoPcscf",
                availableNetwork,
                delayMs = 4000L,
            )
        } else {
            Rlog.d(TAG, "... already using this IMS network")
        }
    }


    private fun handleImsNetworkLinkPropertiesChanged(
        changedNetwork: Network,
        linkProperties: LinkProperties,
    ) {
        Rlog.d(TAG, "IMS network link properties changed ${imsDualSimDebugContext("linkProperties=$linkProperties")}")
        val pcscfs = getPcscfServers(linkProperties)
        val newLocalAddr = getImsLocalAddress(linkProperties)
        val newPcscfAddr = pcscfs.firstOrNull()
        Rlog.d(TAG, "Got pcscfs $pcscfs local=$newLocalAddr")
        if (pcscfs.isNotEmpty() && abandonnedBecauseOfNoPcscf) {
            // Switch to this network if it has P-CSCF (could be a different bearer).
            reconnectIms("P-CSCF appeared after previous no-P-CSCF state", changedNetwork)
            return
        }

        if (!this::network.isInitialized) return

        val oldLocalAddr = if (this::localAddr.isInitialized) localAddr else null
        val oldPcscfAddr = if (this::pcscfAddr.isInitialized) pcscfAddr else null
        val oldRegistrationTech = imsRegistrationTech
        val newRegistrationTech = detectRegistrationTech(linkProperties)

        if (pendingCellularReconnectAfterWfcDisable) {
            val iface = linkProperties.interfaceName ?: ""
            if (newRegistrationTech == REGISTRATION_TECH_IWLAN || iface.startsWith("ipsec")) {
                Rlog.w(
                    TAG,
                    "Pending WFC-disable cellular reconnect; ignoring still-IWLAN IMS link " +
                        "interface=$iface tech=${registrationTechName(newRegistrationTech)}",
                )
                return
            }
            if (newLocalAddr == null || newPcscfAddr == null) {
                Rlog.w(
                    TAG,
                    "Pending WFC-disable cellular reconnect; waiting for usable cellular IMS link " +
                        "interface=$iface local=$newLocalAddr pcscf=$newPcscfAddr",
                )
                return
            }

            pendingCellularReconnectAfterWfcDisable = false
            reconnectIms(
                "cellular IMS link after WFC disabled interface=$iface " +
                    "tech=${registrationTechName(newRegistrationTech)} local=$newLocalAddr pcscf=$newPcscfAddr",
                changedNetwork,
                delayMs = 1_000L,
            )
            return
        }

        val networkChanged = network != changedNetwork
        val localChanged = oldLocalAddr != null && newLocalAddr != null && oldLocalAddr != newLocalAddr
        val pcscfChanged = oldPcscfAddr != null && newPcscfAddr != null && oldPcscfAddr != newPcscfAddr
        val techChanged = imsReady && oldRegistrationTech != newRegistrationTech
        val techOnlyChanged = techChanged && !networkChanged && !localChanged && !pcscfChanged

        if (techOnlyChanged && hasActiveOrPendingCallForImsReconnectDeferral()) {
            val deferredReason = "tech-only IMS link changed during call: " +
                "oldTech=${registrationTechName(oldRegistrationTech)} " +
                "newTech=${registrationTechName(newRegistrationTech)} " +
                "interface=${linkProperties.interfaceName}"
            pendingImsReconnectAfterActiveCallReason = deferredReason
            noteImsAccessChangeDuringPendingIncomingCall(deferredReason)
            Rlog.w(
                TAG,
                "Deferring tech-only IMS reconnect while SIP call is active or pending: " +
                    deferredReason + " " + activeOrPendingCallSummaryForReconnectDeferral(),
            )
            return
        }

        if (networkChanged || localChanged || pcscfChanged || techChanged) {
            reconnectIms(
                "IMS link changed networkChanged=$networkChanged " +
                    "localChanged=$localChanged pcscfChanged=$pcscfChanged " +
                    "techChanged=$techChanged oldLocal=$oldLocalAddr " +
                    "newLocal=$newLocalAddr oldPcscf=$oldPcscfAddr " +
                    "newPcscf=$newPcscfAddr oldTech=${registrationTechName(oldRegistrationTech)} " +
                    "newTech=${registrationTechName(newRegistrationTech)} " +
                    "interface=${linkProperties.interfaceName}",
                changedNetwork,
                delayMs = if (
                    techChanged &&
                        oldRegistrationTech == REGISTRATION_TECH_IWLAN &&
                        newRegistrationTech == REGISTRATION_TECH_LTE
                ) 6_000L else 1_000L,
            )
        }
    }


    private fun createImsNetworkCallback(): ConnectivityManager.NetworkCallback {
        return object : ConnectivityManager.NetworkCallback() {
            override fun onUnavailable() {
                Rlog.d(TAG, "IMS network unavailable ${imsDualSimDebugContext()}")
            }

            override fun onLost(lostNetwork: Network) {
                handleImsNetworkLost(
                    callback = this,
                    lostNetwork = lostNetwork,
                )
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                Rlog.d(TAG, "IMS network blocked status changed ${imsDualSimDebugContext("blocked=$blocked")}")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                handleImsNetworkCapabilitiesChanged(
                    changedNetwork = network,
                    networkCapabilities = networkCapabilities,
                )
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                Rlog.d(TAG, "IMS network losing")
            }

            override fun onLinkPropertiesChanged(
                _network: Network,
                linkProperties: LinkProperties,
            ) {
                handleImsNetworkLinkPropertiesChanged(
                    changedNetwork = _network,
                    linkProperties = linkProperties,
                )
            }

            override fun onAvailable(_network: Network) {
                handleImsNetworkAvailable(_network)
            }
        }
    }


    private fun phhIsAirplaneModeOnForImsAccess(): Boolean {
        return try {
            android.provider.Settings.Global.getInt(
                ctxt.contentResolver,
                android.provider.Settings.Global.AIRPLANE_MODE_ON,
                0,
            ) != 0
        } catch (t: Throwable) {
            Rlog.d(TAG, "Failed to read airplane mode state for IMS access selection", t)
            false
        }
    }

    private fun phhIsIwlanPsRegisteredForImsAccess(): Boolean {
        val serviceState = subTelephonyManager.serviceState ?: return false
        val iwlanInfo = try {
            serviceState.getNetworkRegistrationInfo(
                android.telephony.NetworkRegistrationInfo.DOMAIN_PS,
                android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
            )
        } catch (t: Throwable) {
            Rlog.d(TAG, "Failed to read IWLAN PS registration for IMS access selection", t)
            null
        } ?: return false

        return iwlanInfo.isNetworkRegistered &&
            iwlanInfo.accessNetworkTechnology == TelephonyManager.NETWORK_TYPE_IWLAN
    }

    private fun phhIsInWfcPreferenceConvergenceWindow(): Boolean {
        val elapsedMs = SystemClock.uptimeMillis() - wfcSubscriptionSettingMonitor.lastChangeUptimeMs()
        return elapsedMs in 0L..IWLAN_CONVERGENCE_OUTGOING_CALL_GUARD_MS
    }

    private fun currentWfcImsAccessSnapshot(): WfcImsAccessPolicy.Snapshot =
        WfcImsAccessPolicy.Snapshot(
            wifiOnly = wfcSubscriptionSettingMonitor.isWifiOnly(),
            wifiPreferredOrOnly = wfcSubscriptionSettingMonitor.isWifiPreferredOrOnly(),
            iwlanReady = phhIsIwlanPsRegisteredForImsAccess(),
            airplaneMode = phhIsAirplaneModeOnForImsAccess(),
            convergenceWindow = phhIsInWfcPreferenceConvergenceWindow(),
        )

    private fun logWfcImsRequestPolicyIfNeeded() {
        val snapshot = currentWfcImsAccessSnapshot()
        if (!WfcImsAccessPolicy.shouldPreferIwlanForImsAccessNow(snapshot)) {
            return
        }

        Rlog.w(
            TAG,
            "WFC policy currently prefers/requires IWLAN; keeping telephony IMS " +
                "NetworkRequest on CELLULAR transport so QNS/DataNetwork can select " +
                "IWLAN internally: ${snapshot.toLogString()}",
        )
    }

    private fun warnIfCurrentCellularImsEndpointDoesNotMatchWfcPolicy(lp: LinkProperties) {
        val snapshot = currentWfcImsAccessSnapshot()
        if (!WfcImsAccessPolicy.shouldWarnAboutCellularImsMismatch(
                snapshot = snapshot,
                registeredOverLte = imsRegistrationTech == REGISTRATION_TECH_LTE,
            )
        ) {
            return
        }

        Rlog.w(
            TAG,
            "WFC policy wants IWLAN but framework supplied LTE IMS; accepting LTE " +
                "endpoint to avoid keeping IMS permanently unregistered. This means " +
                "QNS/AccessNetworks did not migrate the IMS APN to IWLAN yet: " +
                "interface=${lp.interfaceName} " +
                "${snapshot.toLogString()} " +
                "caps=${if (this::network.isInitialized) connectivityManager.getNetworkCapabilities(network) else null}",
        )

        // Do not hard reject LTE here. The IMS NetworkRequest is still a
        // telephony request and PhhIms cannot force QNS to hand it over to
        // IWLAN. Rejecting the only satisfied IMS bearer makes Wi-Fi-only mode
        // loop between LTE data setup and local deregistration forever on
        // devices/carriers where QNS reports IWLAN registered but does not make
        // it available for the IMS APN. Keep IMS alive and leave the actual
        // bearer choice to the framework until the QNS/IWLAN qualification issue
        // is fixed in telephony/carrier config.
    }

    fun getVolteNetwork() {
        // TODO add something similar for VoWifi ipsec tunnel?
        Rlog.d(TAG, "Requesting IMS network ${imsDualSimDebugContext()}")
        if (!isRatReadyForImsNetworkRequest()) {
            Rlog.w(TAG, "Deferring IMS network request until LTE/NR/IWLAN is back")
            scheduleImsNetworkRequestRestart("RAT not ready for IMS network request", 3_000L)
            return
        }
        logWfcImsRequestPolicyIfNeeded()
        val imsNetworkRequest = ImsNetworkRequestBuilder.buildForSubscription(subId)

        Rlog.d(TAG, "Built subscription-specific IMS network request ${imsDualSimDebugContext("transport=CELLULAR request=$imsNetworkRequest")}")

        unregisterImsNetworkCallback("new IMS network request")

        val callback = createImsNetworkCallback()

        imsNetworkCallback = callback
        connectivityManager.requestNetwork(imsNetworkRequest, callback)
    }

    fun updateCommonHeaders(socket: SipConnection) {
        // Note: we are giving serverSocket (TCP) port, but TCP and UDP servers use the same port
        val update = SipCommonHeaderBuilder.build(
            socket = socket,
            serverPort = serverSocket.localPort,
            imei = imei,
            imsi = imsi,
        )
        contact = update.contact
        registerHeaders += update.headers
        commonHeaders += update.headers
    }


    private fun useSingTelNullSha1SecurityOffer(): Boolean =
        realm.equals("ims.mnc001.mcc525.3gppnetwork.org", ignoreCase = true) ||
            registerTargetRealm.equals("ims.singtel.com", ignoreCase = true)

    fun register(_writer: OutputStream? = null) {
        RegistrationCellInfoLogger.log(TAG, subTelephonyManager)

        // XXX samsung rom apparently regenerates local SPIC/SPIS every register,
        // this doesn't affect current connections but possibly affects new incoming
        // connections ? Just keep it constant for now
        // XXX samsung doesn't increment cnonce but it would be better to avoid replays?
        // well that'd only matter if the server refused replays, so keep as is.
        // XXX timeout/retry? notification on fail? receive on thread?

        val writer = _writer ?: socket.gWriter()

        val msg = SipRegisterRequestBuilder.build(
            realm = registerTargetRealm,
            registerHeaders = registerHeaders,
            registerCounter = registerCounter,
            contact = contact,
            akaDigest = akaDigest,
            ipsecSettings = ipsecSettings,
            clientPort = socket.gLocalPort(),
            serverPort = serverSocket.localPort,
            securityClientOverride = registerSecurityClientOverride,
            securityClientAlgs = if (useSingTelNullSha1SecurityOffer()) listOf("hmac-sha-1-96") else listOf("hmac-sha-1-96", "hmac-md5-96"),
            securityClientEalgs = if (useSingTelNullSha1SecurityOffer()) listOf("null") else listOf("null", "aes-cbc"),
            stripSecurityVerifyQ = false,
            useSelectedSecurityClient = registerTargetRealm != realm,
            forceSecurityAgreementNullEalg = false,
        )
        Rlog.d(TAG, "Sending $msg")
        synchronized(writer) {
            writer.write(msg.toByteArray())
            writer.flush()
        }
        registerCounter += 1
    }

    fun registerCallback(response: SipResponse): Boolean {
        // once we get there all register must be successful
        // on failure just abort thread, ims will restart
        require(response.statusCode == 200)

        val registeredIdentity = SipRegisterSuccessParser.parse(response)
        mySip = registeredIdentity.mySip
        myTel = registeredIdentity.myTel
        commonHeaders += registeredIdentity.commonHeaders()

        subscribe()

        // REGISTER 200 OK is the actual IMS registration success.  Do not
        // block framework registration state on the optional reg-event
        // SUBSCRIBE path; some carriers answer it very late with 504.
        refreshRegistrationTechFromCurrentLink("REGISTER 200 OK")
        markImsReady("REGISTER 200 OK")

        // always keep callback
        return false
    }

    fun subscribe() {
        val msg = SipRegEventSubscribeBuilder.build(
            mySip = mySip,
            myTel = myTel,
            commonHeaders = commonHeaders,
            socket = socket,
            serverPort = serverSocket.localPort,
            imei = imei,
        )
        setResponseCallback(msg.headers["call-id"]!![0], ::subscribeCallback)
        Rlog.d(TAG, "Sending $msg")
        writeSipBytesWithFlush(socket.gWriter(), "SipHandler msg", msg.toByteArray())
    }

    fun subscribeCallback(response: SipResponse): Boolean {
        if (response.statusCode !in 200..299) {
            Rlog.w(TAG, "SUBSCRIBE reg-event failed after REGISTER success: ${response.statusCode} ${response.statusString}")
            return true
        }

        markImsReady("SUBSCRIBE ${response.statusCode}")
        return true
    }

    fun waitPrack(v: Int) {
        prAckWaitTracker.waitFor(v)
    }

    private fun localDialogHeadersForRequest(call: Call, method: SipMethod): SipHeadersMap =
        SipDialogHeaderBuilder.localDialogHeadersForRequest(
            call = call,
            method = method,
            commonHeaders = commonHeaders,
            contact = contact,
        )

    fun handleAck(request: SipRequest): Int {
        val callId = request.callIdOrEmpty()
        val call = currentCall
        val currentCallId = call?.callIdOrNull()
        Rlog.d(TAG, SipAckHandling.receivedLog(callId, currentCallId, call?.outgoing))
        if (call != null && !call.outgoing && currentCallId == callId) {
            callStarted.set(true)
            incomingAcceptedAwaitingAck.set(false)

            if (threadsStarted.compareAndSet(false, true)) {
                Rlog.d(TAG, SipAckHandling.startIncomingMediaLog())
                callDecodeThread()
                callEncodeThread(callSnapshot = call)
            } else {
                Rlog.d(TAG, SipAckHandling.incomingMediaAlreadyStartedLog())
            }

            onIncomingCallConnected?.invoke(
                Object(),
                mapOf("call-id" to callId) + SipAudioCodecNegotiator.audioCodecExtras(call.audioCodec),
            )

            if (incomingHangupAfterAck.getAndSet(false)) {
                Rlog.d(TAG, SipRemoteDialogTermination.deferredLocalByeAfterAckLog())
                sendByeForCall(call)
                rememberTerminatedIncomingCall(
                    callId,
                    SipRemoteDialogTermination.deferredLocalByeAfterAckReason(),
                )
            currentCall = null
            }
        }
        return SipAckHandling.okStatus()
    }

    fun handlePrack(request: SipRequest): Int {
        val rackHeader = SipPrackHandling.rackHeader(request)
        Rlog.d(TAG, SipPrackHandling.receivedLog(rackHeader))
        val id = SipPrackHandling.rackId(rackHeader)
        prAckWaitTracker.ack(id)
        return SipPrackHandling.okStatus()
    }


    private fun updateResponseWriterFor(request: SipRequest): java.io.OutputStream {
        val updateCallId = request.headers.callIdOrNull()
        return updateCallId?.let { dispatcher.writerForCallId(it) } ?: socket.gWriter()
    }


    private fun updateCurrentCallFromUpdateSdp(
        call: Call,
        request: SipRequest,
        answerSdp: ByteArray,
        amrTrack: Int,
        amrTrackDesc: String,
        dtmfTrack: Int,
        dtmfTrackDesc: String,
        rtpRemoteAddr: InetAddress,
        rtpRemotePort: Int,
    ): Call {
        val updateState = SipUpdateSdpCallUpdate.state(
            request = request,
            answerSdp = answerSdp,
            amrTrack = amrTrack,
            amrTrackDesc = amrTrackDesc,
            dtmfTrack = dtmfTrack,
            dtmfTrackDesc = dtmfTrackDesc,
            rtpRemoteAddr = rtpRemoteAddr,
            rtpRemotePort = rtpRemotePort,
            fallbackRemoteContact = call.remoteContact,
            extractDestinationFromContact = { contact -> extractDestinationFromContact(contact) },
        )
        val updatedCall = call.copy(
            amrTrack = updateState.amrTrack,
            amrTrackDesc = updateState.amrTrackDesc,
            dtmfTrack = updateState.dtmfTrack,
            dtmfTrackDesc = updateState.dtmfTrackDesc,
            rtpRemoteAddr = updateState.rtpRemoteAddr,
            rtpRemotePort = updateState.rtpRemotePort,
            sdp = updateState.answerSdp,
            remoteContact = updateState.remoteContact,
        )
        currentCall = updatedCall
        return updatedCall
    }


    private data class UpdateSdpAnswerState(
        val updatedCall: Call,
        val answerSdp: ByteArray,
    )

    private fun prepareUpdateSdpAnswer(
        request: SipRequest,
        call: Call,
        requestCallId: String,
        updateSdpOffer: UpdateSdpOffer,
    ): UpdateSdpAnswerState? {
        val rtpRemoteAddr = updateSdpOffer.rtpRemoteAddr
        val rtpRemotePort = updateSdpOffer.rtpRemotePort
        val offeredPayloads = updateSdpOffer.offeredPayloads
        val attributes = updateSdpOffer.attributes

        val mediaSelection = SipUpdateSdpMediaSelector.select(
            logTag = TAG,
            attributes = attributes,
            offeredPayloads = offeredPayloads,
            selectedAudioCodec = call.audioCodec,
            requestCallId = requestCallId,
        ) ?: return null
        val selectedAudioCodec = mediaSelection.selectedAudioCodec
        val amrTrack = mediaSelection.amrTrack
        val amrTrackDesc = mediaSelection.amrTrackDesc
        val amrFmtpAnswer = mediaSelection.amrFmtpAnswer
        val dtmfTrack = mediaSelection.dtmfTrack
        val dtmfTrackDesc = mediaSelection.dtmfTrackDesc

        SipUpdateRtpEndpointConnector.connectIfNeeded(
            call = call,
            rtpRemoteAddr = rtpRemoteAddr,
            rtpRemotePort = rtpRemotePort,
            requestCallId = requestCallId,
            logTag = TAG,
        )

        val answerSdp = SipUpdateSdpAnswerBuilder.build(
            request = request,
            call = call,
            attributes = attributes,
            amrTrack = amrTrack,
            amrTrackDesc = amrTrackDesc,
            amrFmtpAnswer = amrFmtpAnswer,
            dtmfTrack = dtmfTrack,
            dtmfTrackDesc = dtmfTrackDesc,
            localAddr = socket.gLocalAddr(),
        )

        val updatedCall = updateCurrentCallFromUpdateSdp(
            call = call,
            request = request,
            answerSdp = answerSdp,
            amrTrack = amrTrack,
            amrTrackDesc = amrTrackDesc,
            dtmfTrack = dtmfTrack,
            dtmfTrackDesc = dtmfTrackDesc,
            rtpRemoteAddr = rtpRemoteAddr,
            rtpRemotePort = rtpRemotePort,
        )

        return UpdateSdpAnswerState(
            updatedCall = updatedCall,
            answerSdp = answerSdp,
        )
    }

    fun handleUpdate(request: SipRequest): Int {
        val requestCallId = request.callIdOrEmpty()
        val requestCseq = request.headers["cseq"]?.getOrNull(0).orEmpty()
        val call = currentCall
        val currentCallId = call?.callIdOrNull()

        if (call == null || currentCallId != requestCallId) {
            Rlog.w(
                TAG,
                SipUpdateDialogValidator.nonCurrentDialogLog(
                    requestCallId = requestCallId,
                    requestCseq = requestCseq,
                    currentCallId = currentCallId,
                ),
            )
            return SipUpdateDialogValidator.nonCurrentDialogStatus()
        }

        val updateResponseWriter = updateResponseWriterFor(request)

        val isSdp = SipUpdateDialogValidator.isSdpUpdate(request)

        if (!isSdp) {
            SipUpdateResponseWriter.writeOkWithoutSdp(
                request = request,
                requestCallId = requestCallId,
                updateResponseWriter = updateResponseWriter,
                logTag = TAG,
            )
            return 0
        }

        val updateSdpOffer = SipUpdateSdpOfferParser.parse(
            request = request,
            requestCallId = requestCallId,
            requestCseq = requestCseq,
            logTag = TAG,
        ) ?: return 488
        val updateSdpAnswerState = prepareUpdateSdpAnswer(
            request = request,
            call = call,
            requestCallId = requestCallId,
            updateSdpOffer = updateSdpOffer,
        ) ?: return 488

        SipUpdateResponseWriter.writeSdpAnswerAndRingingIfNeeded(
            request = request,
            call = call,
            updateResponseWriter = updateResponseWriter,
            updatedCallId = updateSdpAnswerState.updatedCall.callIdOrEmpty(),
            answerSdp = updateSdpAnswerState.answerSdp,
            logTag = TAG,
        )

        return 0
    }

    private fun acknowledgeLateCancelAfterFinalResponse(
        request: SipRequest,
        callId: String,
    ): Boolean {
        if (request.method != SipMethod.CANCEL || !incomingFinalResponseSent.get()) {
            return false
        }

        Rlog.d(TAG, SipRemoteDialogTermination.lateCancelReceivedAfterFinalResponseLog())
        val toOverride = currentCall?.callHeaders?.get("to") ?: request.headers["to"]
        val response = SipRemoteDialogTermination.lateCancelOkResponse(
            request = request,
            toOverride = toOverride,
        )
        Rlog.d(TAG, SipRemoteDialogTermination.lateCancelOkLog(response))
        val cancelResponseWriter = dispatcher.writerForCallId(callId) ?: currentCall?.incomingResponseWriter ?: socket.gWriter()
        SipRemoteDialogTermination.writeResponse(
            responseWriter = cancelResponseWriter,
            response = response,
        )

        return true
    }

    private fun handleRemoteCancelTransaction(
        request: SipRequest,
        callId: String,
    ): Boolean {
        if (request.method != SipMethod.CANCEL) {
            return false
        }

        val cancelResponseWriter = currentCall?.incomingResponseWriter ?: dispatcher.writerForCallId(callId) ?: socket.gWriter()
        val toOverride = currentCall?.callHeaders?.get("to") ?: request.headers["to"]

        // RFC 3261: CANCEL is its own transaction. Reply 200 OK to the CANCEL,
        // then terminate the original INVITE transaction with 487 using CSeq: INVITE.
        // Do not let parseMessage emit an extra generic 200 OK with a different To tag.
        val cancelOk = SipRemoteDialogTermination.cancelOkResponse(
            request = request,
            toOverride = toOverride,
        )
        Rlog.d(TAG, SipRemoteDialogTermination.cancelOkLog(cancelOk))
        SipRemoteDialogTermination.writeResponse(
            responseWriter = cancelResponseWriter,
            response = cancelOk,
        )

        val inviteTerminated = SipRemoteDialogTermination.cancelledInviteResponse(
            request = request,
            toOverride = toOverride,
        )
        Rlog.d(TAG, SipRemoteDialogTermination.cancelledInviteLog(inviteTerminated))
        SipRemoteDialogTermination.writeResponse(
            responseWriter = cancelResponseWriter,
            response = inviteTerminated,
        )

        rememberTerminatedIncomingCall(callId, SipRemoteDialogTermination.remoteCancelTerminationReason())
        currentCall = null
        clearPendingOutgoingInvite(
            callId,
            closeRtpSocket = false,
            reason = SipRemoteDialogTermination.remoteCancelTerminationReason(),
        )
        onCancelledCall?.invoke(
            Object(),
            "",
            SipRemoteDialogTermination.remoteCancelCancellationExtras(callId),
        )
        return true
    }

    private fun handleRemoteDialogTerminationAfterCleanup(
        request: SipRequest,
        callId: String,
        isBye: Boolean,
    ): Int {
        if (!isBye) {
            Rlog.w(TAG, SipRemoteDialogTermination.unexpectedNonByeDialogTerminationLog(request.method))
        }

        val remoteMethodReason = SipRemoteDialogTermination.remoteMethodReason(request.method)
        if (currentCall?.outgoing == false) rememberTerminatedIncomingCall(callId, remoteMethodReason)
        val terminatedCall = currentCall
        currentCall = null
        clearPendingOutgoingInvite(callId, closeRtpSocket = false, reason = remoteMethodReason)
        val cancelExtras = SipRemoteDialogTermination.remoteEndExtras(
            logTag = TAG,
            callId = callId,
            isBye = isBye,
            isOutgoingCall = terminatedCall?.outgoing == true,
            outgoingConnectedNotified = terminatedCall?.outgoingConnectedNotified?.get() == true,
        )
        onCancelledCall?.invoke(Object(), "", cancelExtras)
        return 200
    }

    fun handleCancel(request: SipRequest): Int {
        val callId = request.callIdOrEmpty()
        val isBye = request.method == SipMethod.BYE

        // RFC 3261 §9.2: CANCEL has no effect if we already sent a final response
        // for the INVITE. Reply 200 OK to the CANCEL transaction, but keep the
        // dialog/runtime alive until the final ACK or a real BYE arrives.
        //
        // Some networks can race a late CANCEL against our final 200 OK. Clearing
        // currentCall here makes the local UI drop immediately even though the
        // dialog has already been answered and the remote side will usually send
        // a BYE to terminate the established dialog.
        if (acknowledgeLateCancelAfterFinalResponse(
                request = request,
                callId = callId,
            )
        ) return 0

        stopCallRuntime(SipRemoteDialogTermination.cleanupReason())
        prAckWaitTracker.clearAndNotifyAll()

        Rlog.d(TAG, SipRemoteDialogTermination.cancelledCallLog(callId, request.method))

        if (handleRemoteCancelTransaction(
                request = request,
                callId = callId,
            )
        ) return 0

        return handleRemoteDialogTerminationAfterCleanup(
            request = request,
            callId = callId,
            isBye = isBye,
        )
    }

    data class Call(
        val outgoing: Boolean,
        val callHeaders: SipHeadersMap,
        val sdp: ByteArray,
        val audioCodec: NegotiatedAudioCodec,
        val amrTrack: Int,
        val amrTrackDesc: String,
        val dtmfTrack: Int,
        val dtmfTrackDesc: String,
        val rtpRemoteAddr: InetAddress,
        val rtpRemotePort: Int,
        val rtpSocket: DatagramSocket,
        val hasEarlyMedia: Boolean,
        val remoteContact: String,
        val incomingResponseWriter: OutputStream? = null,
        val localCseq: AtomicInteger = AtomicInteger(2),
        val localSdpVersion: AtomicInteger = AtomicInteger(2), val outgoingRtpReceived: AtomicBoolean = AtomicBoolean(false), val outgoingConnectedNotified: AtomicBoolean = AtomicBoolean(false), )

    // illegal SDP conservative retry: retry once only when the SBC explicitly rejects the SDP body.


    private fun retryOutgoingInviteAfterIllegalSdp(
        pending: PendingOutgoingInvite,
        response: SipResponse,
        outgoingDialogNextCseq: AtomicInteger,
    ): Boolean {
        if (!SipOutgoingInviteRetryPolicy.responseWarnsIllegalSdp(response)) return false
        if (pending.cancelSent.get()) {
            Rlog.w(TAG, SipOutgoingInviteRetryPolicy.notRetryingIllegalSdpAfterCancelLog(pending.callId))
            return false
        }
        if (!pending.retriedAfterIllegalSdp.compareAndSet(false, true)) {
            Rlog.w(TAG, SipOutgoingInviteRetryPolicy.notRetryingIllegalSdpTwiceLog(pending.callId))
            return false
        }

        val oldCseq = SipOutgoingInviteRetryPolicy.originalInviteCseq(pending.headers)
        val retryCseq = SipOutgoingInviteRetryPolicy.nextInviteCseq(oldCseq)
        val retryBody = SipOutgoingInviteRetryPolicy.conservativeAmrNbRetryBody(
            originalBody = pending.body,
            localHost = socket.gLocalAddr().hostAddress ?: "0.0.0.0",
        )
        val retryHeaders = SipOutgoingInviteRetryPolicy.retryHeaders(pending.headers, retryCseq)
        val retryInvite = SipOutgoingInviteRetryPolicy.illegalSdpRetryInvite(
            destination = pending.destination,
            retryHeaders = retryHeaders,
            retryBody = retryBody,
        )

        pendingOutgoingInvite = pending.copy(
            headers = retryInvite.headers,
            body = retryBody,
        )

        val desiredNextCseq = retryCseq + 1
        while (true) {
            val oldNextCseq = outgoingDialogNextCseq.get()
            if (oldNextCseq >= desiredNextCseq ||
                outgoingDialogNextCseq.compareAndSet(oldNextCseq, desiredNextCseq)
            ) break
        }

        Rlog.w(
            TAG,
            SipOutgoingInviteRetryPolicy.illegalSdpRetryLog(
                callId = pending.callId,
                oldCseq = oldCseq,
                retryCseq = retryCseq,
                oldBytes = pending.body.size,
                retryBytes = retryBody.size,
                dualSimDebugContext = imsDualSimDebugContext(),
            ),
        )
        writeSipBytesWithFlush(
            socket.gWriter(),
            SipOutgoingInviteRetryPolicy.illegalSdpRetryWriteLabel(),
            retryInvite.toByteArray(),
        )
        return true
    }


    private fun retryOutgoingInviteAfter422(
        pending: PendingOutgoingInvite,
        response: SipResponse,
        outgoingDialogNextCseq: AtomicInteger,
    ): Boolean {
        val retry = inviteSessionTimerPolicy.buildRetryHeadersAfter422(
            realm = realm,
            originalHeaders = pending.headers,
            response = response,
        ) ?: return false

        if (!pending.retriedAfter422.compareAndSet(false, true)) {
            Rlog.w(TAG, SipOutgoingInviteRetryPolicy.notRetryingAfter422TwiceLog(pending.callId))
            return false
        }

        val retryInvite = SipOutgoingInviteRetryPolicy.retryInviteAfter422(
            destination = pending.destination,
            retryHeaders = retry.headers,
            body = pending.body,
        )

        pendingOutgoingInvite = pending.copy(headers = retryInvite.headers)
        val desiredNextCseq = SipOutgoingInviteRetryPolicy.desiredNextCseqAfter422Retry(retry.cseqNumber)
        while (true) {
            val oldNextCseq = outgoingDialogNextCseq.get()
            if (oldNextCseq >= desiredNextCseq ||
                outgoingDialogNextCseq.compareAndSet(oldNextCseq, desiredNextCseq)
            ) break
        }

        Rlog.w(
            TAG,
            SipOutgoingInviteRetryPolicy.retryAfter422Log(
                callId = pending.callId,
                minSeSeconds = retry.minSeSeconds,
                sessionExpiresSeconds = retry.sessionExpiresSeconds,
                cseqNumber = retry.cseqNumber,
            ),
        )
        val writer = socket.gWriter()
        synchronized(writer) {
            writer.write(retryInvite.toByteArray())
            writer.flush()
        }
        return true
    }

    // AMR-NB speech payload sizes in bits for FT 0..8.
    // Codec input for Android's audio/3gpp decoder is one AMR storage frame:
    //   [frame header: 0 | FT(4) | Q | 00] + speech bits octet padded.
    // The RTP payloads used here are RFC 4867 bandwidth-efficient packets:
    //   CMR(4), F(1), FT(4), Q(1), speech bits...

    private fun sendUplinkSilencePacketForCall(
        fallbackCall: Call,
        callId: String,
        audioCodec: NegotiatedAudioCodec,
        sequenceNumber: Int,
        timestamp: Int,
        label: String,
    ): Boolean {
        val sendCall = currentCall?.takeIf { it.callIdOrEmpty() == callId } ?: fallbackCall
        return SipUplinkSilenceRtpSender.sendNoDataPacket(
            logTag = TAG,
            audioCodec = audioCodec,
            payloadType = sendCall.amrTrack,
            sequenceNumber = sequenceNumber,
            timestamp = timestamp,
            rtpSocket = sendCall.rtpSocket,
            remoteAddr = sendCall.rtpRemoteAddr,
            remotePort = sendCall.rtpRemotePort,
            label = label,
        )
    }


    private fun sendUplinkSilenceUntilCallStarted(
        call: Call,
        callId: String,
        audioCodec: NegotiatedAudioCodec,
        encoder: android.media.MediaCodec,
        generation: Int,
    ): Boolean {
        return SipUplinkSilencePacer.sendUntilCallStarted(
            logTag = TAG,
            callStarted = callStarted,
            callStopped = callStopped,
            callGeneration = callGeneration,
            generation = generation,
            nextSequenceNumber = { rtpSequenceNumber.getAndIncrement() },
            nextTimestamp = { rtpTimestampSamples.getAndAdd(audioCodec.rtpTimestampStep) },
            totalPacketsSent = { rtpSequenceNumber.get() },
            sendPacket = { sequenceNumber, timestamp ->
                sendUplinkSilencePacketForCall(
                    fallbackCall = call,
                    callId = callId,
                    audioCodec = audioCodec,
                    sequenceNumber = sequenceNumber,
                    timestamp = timestamp,
                    label = SipUplinkEncodeThreadLog.rtpPacketLabel(sequenceNumber),
                )
            },
            cleanupOnExit = {
                encoder.stop()
                encoder.release()
            },
        )
    }


    private fun delayIncomingMicStartAfterAcceptIfNeeded(
        call: Call,
        callId: String,
        audioCodec: NegotiatedAudioCodec,
        encoder: android.media.MediaCodec,
        generation: Int,
        incomingMicStartDelayMs: Long,
        reason: String,
    ): Boolean {
        if (call.outgoing || incomingMicStartDelayMs <= 0L) return true

        return SipUplinkIncomingMicSettlePacer.delayBeforeMicStart(
            logTag = TAG,
            delayMs = incomingMicStartDelayMs,
            reason = reason,
            callStopped = callStopped,
            callGeneration = callGeneration,
            generation = generation,
            nextSequenceNumber = { rtpSequenceNumber.getAndIncrement() },
            nextTimestamp = { rtpTimestampSamples.getAndAdd(audioCodec.rtpTimestampStep) },
            sendPacket = { sequenceNumber, timestamp ->
                sendUplinkSilencePacketForCall(
                    fallbackCall = call,
                    callId = callId,
                    audioCodec = audioCodec,
                    sequenceNumber = sequenceNumber,
                    timestamp = timestamp,
                    label = SipUplinkEncodeThreadLog.incomingSettleSilenceLabel(sequenceNumber),
                )
            },
            cleanupOnExit = {
                try { encoder.stop() } catch (_: Throwable) { }
                try { encoder.release() } catch (_: Throwable) { }
            },
        )
    }


    private fun runUplinkAudioCaptureAfterMicStart(
        audioCodec: NegotiatedAudioCodec,
        encoder: android.media.MediaCodec,
        generation: Int,
    ): Boolean {
        val capture = SipUplinkAudioCaptureStarter.start(
            logTag = TAG,
            context = ctxt,
            audioCodec = audioCodec,
            encoder = encoder,
        ) ?: return false
        val audioRecord = capture.audioRecord
        val minBufferSize = capture.bufferSize
        val prevAudioMode = capture.previousAudioMode
        SipUplinkAudioLoop.run(
            logTag = TAG,
            audioRecord = audioRecord,
            bufferSize = minBufferSize,
            encoder = encoder,
            audioCodec = audioCodec,
            callStopped = callStopped,
            callGeneration = callGeneration,
            generation = generation,
            gainQ8 = imsUplinkGainQ8,
            nextSequenceNumber = { rtpSequenceNumber.getAndIncrement() },
            nextTimestamp = { rtpTimestampSamples.getAndAdd(audioCodec.rtpTimestampStep) },
            sendFrame = sendFrame@{ sequenceNumber, timestamp, storageFrame, marker, frameType, frameSize, frameCount ->
                val sendCall = currentCall ?: return@sendFrame false
                SipUplinkMediaRtpSender.sendStorageFrame(
                    logTag = TAG,
                    audioCodec = audioCodec,
                    payloadType = sendCall.amrTrack,
                    sequenceNumber = sequenceNumber,
                    timestamp = timestamp,
                    storageFrame = storageFrame,
                    marker = marker,
                    rtpSocket = sendCall.rtpSocket,
                    remoteAddr = sendCall.rtpRemoteAddr,
                    remotePort = sendCall.rtpRemotePort,
                    frameType = frameType,
                    frameSize = frameSize,
                    realFrameCount = frameCount,
                )
            },
        )
        SipUplinkAudioCleanup.cleanup(
            logTag = TAG,
            context = ctxt,
            audioRecord = audioRecord,
            encoder = encoder,
            callStopped = callStopped,
            callGeneration = callGeneration,
            generation = generation,
            totalPacketsSent = rtpSequenceNumber.get(),
            previousAudioMode = prevAudioMode,
        )
        return true
    }


    private fun runUplinkEncodeThread(
        call: Call,
        callId: String,
        audioCodec: NegotiatedAudioCodec,
        generation: Int,
        incomingMicStartDelayMs: Long,
        reason: String,
    ) {
        rtpSequenceNumber.set(0)
        rtpTimestampSamples.set(0)
        rtpDtmfTimestampSamples.set(0)
        Rlog.d(
            TAG,
            SipUplinkEncodeThreadLog.encodeThreadStarted(
                codecName = audioCodec.name,
                sampleRate = audioCodec.sampleRate,
                callId = callId,
                amrTrack = call.amrTrack,
                remote = "${call.rtpRemoteAddr}:${call.rtpRemotePort}",
                generation = generation,
            ),
        )
        val encoder = SipAudioCodecFactory.createStartedEncoder(
            audioCodec = audioCodec,
        )

        if (!sendUplinkSilenceUntilCallStarted(
                call = call,
                callId = callId,
                audioCodec = audioCodec,
                encoder = encoder,
                generation = generation,
            )
        ) return

        if (!delayIncomingMicStartAfterAcceptIfNeeded(
                call = call,
                callId = callId,
                audioCodec = audioCodec,
                encoder = encoder,
                generation = generation,
                incomingMicStartDelayMs = incomingMicStartDelayMs,
                reason = reason,
            )
        ) return

        runUplinkAudioCaptureAfterMicStart(
            audioCodec = audioCodec,
            encoder = encoder,
            generation = generation,
        )
    }

    fun callEncodeThread(
        incomingMicStartDelayMs: Long = 0L,
        reason: String = "default",
        callSnapshot: Call? = null,
    ) {
        val call = callSnapshot ?: currentCall
        if (call == null) {
            Rlog.w(TAG, SipUplinkEncodeThreadLog.noCurrentCallLog(reason))
            return
        }
        val startState = SipUplinkEncodeThreadLog.startState(
            call = call,
            generation = callGeneration.get(),
        )
        thread {
            runUplinkEncodeThread(
                call = call,
                callId = startState.callId,
                audioCodec = startState.audioCodec,
                generation = startState.generation,
                incomingMicStartDelayMs = incomingMicStartDelayMs,
                reason = reason,
            )
        }
    }

    var currentCall: Call? = null
    private var pendingOutgoingInvite: PendingOutgoingInvite? = null
    private fun logDuplicateOutgoingConnectedOnce(callId: String, reason: String) {
        val key = "${callId.ifBlank { "<blank>" }}|$reason"
        if (!outgoingConnectedDuplicateLogKeys.add(key)) return

        if (callId.isBlank()) {
            logDuplicateOutgoingConnectedOnce("", reason)
        } else {
            logDuplicateOutgoingConnectedOnce(callId, reason)
        }
    }

    private fun maybeNotifyOutgoingCallConnected(call: Call, reason: String) {
        if (!call.outgoing) return

        val callId = call.callIdOrEmpty()
        val activeCallId = currentCall?.callIdOrEmpty().orEmpty()

        if (activeCallId != callId) {
            Rlog.d(TAG, SipOutgoingCallConnectionLogs.staleConnectedNotifyLog(callId, activeCallId, reason))
            return
        }

        if (!callStarted.get()) {
            Rlog.d(TAG, SipOutgoingCallConnectionLogs.rtpBeforeFinalAnswerLog(callId, reason))
            return
        }

        if (!call.outgoingRtpReceived.get()) {
            Rlog.d(TAG, SipOutgoingCallConnectionLogs.noPostAnswerRtpYetLog(callId, reason))
            return
        }

        if (callId.isBlank()) {
            if (!call.outgoingConnectedNotified.compareAndSet(false, true)) {
                logDuplicateOutgoingConnectedOnce("", reason)
                return
            }
        } else {
            if (!outgoingConnectedCallIds.add(callId)) {
                call.outgoingConnectedNotified.set(true)
                logDuplicateOutgoingConnectedOnce(callId, reason)
                return
            }
            call.outgoingConnectedNotified.set(true)
        }

        Rlog.d(TAG, SipOutgoingCallConnectionLogs.connectedAfterRemoteRtpLog(callId, reason))
        onOutgoingCallConnected?.invoke(
            Object(),
            mapOf("call-id" to callId, "connectedReason" to reason) +
                SipAudioCodecNegotiator.audioCodecExtras(call.audioCodec),
        )
    }

    private fun scheduleOutgoingPostAnswerRtpTimeout(callId: String, timeoutMs: Long = 2_000L) {
        thread {
            try {
                Thread.sleep(timeoutMs)
                val activeCall = currentCall ?: return@thread
                val activeCallId = activeCall.callHeaders["call-id"]?.getOrNull(0).orEmpty()
                if (!activeCall.outgoing || activeCallId != callId) return@thread
                if (activeCall.outgoingConnectedNotified.get() || activeCall.outgoingRtpReceived.get()) return@thread
                if (!callStarted.get()) return@thread

                Rlog.w(TAG, SipOutgoingCallConnectionLogs.postAnswerRtpTimeoutLog(timeoutMs, callId))
                callId?.let { outgoingConnectedCallIds.remove(it) }
                stopCallRuntime(SipOutgoingCallConnectionLogs.postAnswerRtpTimeoutReason())
                try {
                    sendByeForCall(activeCall)
                } catch (t: Throwable) {
                    Rlog.w(TAG, SipOutgoingCallConnectionLogs.failedByeForNoMediaTimeoutLog(callId), t)
                }
                currentCall = null
                clearPendingOutgoingInvite(
                    callId,
                    closeRtpSocket = false,
                    reason = SipOutgoingCallConnectionLogs.postAnswerRtpTimeoutReason(),
                )
                onCancelledCall?.invoke(
                    Object(),
                    "",
                    SipOutgoingCallConnectionLogs.postAnswerRtpTimeoutCancellationExtras(callId),
                )
            } catch (t: Throwable) {
                Rlog.e(TAG, SipOutgoingCallConnectionLogs.postAnswerRtpTimeoutFailedLog(callId), t)
            }
        }
    }

    private fun completeIncomingPreconditionAnswerSdp(answerSdp: ByteArray, callId: String): ByteArray =
        SipIncomingInviteFinalSdp.completePreconditionAnswer(
            logTag = TAG,
            answerSdp = answerSdp,
            callId = callId,
        )


    private fun okAcceptedIncomingInviteFinalResponse(
        call: Call,
        omitFinalSdp: Boolean,
    ): SipResponse {
        val finalBody = if (!omitFinalSdp) call.sdp else ByteArray(0)
        return SipIncomingInviteFinalResponses.acceptedFinalResponse(
            callHeaders = call.callHeaders,
            contact = call.callHeaders["contact"]!!.first(),
            body = finalBody,
            omitFinalSdp = omitFinalSdp,
        )
    }


    private fun sendAcceptedIncomingInviteFinalResponse(
        call: Call,
        response: SipResponse,
        acceptedCallId: String,
    ): IncomingInviteFinalResponseWrite? {
        val responseWriter = call.incomingResponseWriter ?: socket.gWriter()
        val responseBytes = response.toByteArray()
        Rlog.d(
            TAG,
            SipIncomingInviteFinalResponses.acceptedFinalResponseWriteLog(
                response = response,
                hasIncomingResponseWriter = call.incomingResponseWriter != null,
            ),
        )
        incomingFinalResponseSent.set(true)
        incomingAcceptedAwaitingAck.set(true)
        if (
            !writeSipBytes(
                responseWriter,
                responseBytes,
                SipIncomingInviteFinalResponses.acceptedFinalResponseWriteContext(acceptedCallId),
            )
        ) {
            incomingFinalResponseSent.set(false)
            incomingAcceptedAwaitingAck.set(false)
            incomingHangupAfterAck.set(false)
            onCancelledCall?.invoke(
                Object(),
                "",
                SipIncomingInviteFinalResponses.acceptedFinalResponseWriteFailureCancellationExtras(acceptedCallId),
            )
            return null
        }
        incomingFinalResponseSent.set(true)
        incomingAcceptedAwaitingAck.set(true)
        incomingHangupAfterAck.set(false)
        return IncomingInviteFinalResponseWrite(
            responseWriter = responseWriter,
            responseBytes = responseBytes,
        )
    }


    private fun prewarmIncomingMediaAfterAccept(call: Call) {
        when (
            val action = SipIncomingInviteFinalResponses.mediaPrewarmAction(
                startedNow = threadsStarted.compareAndSet(false, true),
            )
        ) {
            IncomingAcceptMediaPrewarmAction.START -> {
                Rlog.d(TAG, SipIncomingInviteFinalResponses.mediaPrewarmLogMessage(action))
                callDecodeThread()
                callEncodeThread(
                    incomingMicStartDelayMs = 250L,
                    reason = SipIncomingInviteFinalResponses.incomingAckAudioRouteSettleReason(),
                    callSnapshot = call,
                )
            }
            IncomingAcceptMediaPrewarmAction.ALREADY_STARTED -> {
                Rlog.d(TAG, SipIncomingInviteFinalResponses.mediaPrewarmLogMessage(action))
            }
        }
    }


    private fun startIncomingInviteFinalResponseRetransmit(
        acceptedCallId: String,
        responseWriter: OutputStream,
        responseBytes: ByteArray,
    ) {
        // RFC 3261: 2xx responses to INVITE are end-to-end and must be retransmitted
        // by the UAS core until the matching ACK arrives. This is also useful here as a
        // diagnostic: if the first 200 OK is lost/ignored on the IMS TCP flow, repeated
        // 200 OKs should make the missing-ACK problem visible in the log/network trace.
        thread(name = "PhhIncoming2xxRetransmit") {
            var delayMs = SipIncomingInviteFinalResponses.initialFinalResponseRetransmitDelayMs()
            var elapsedMs = 0L
            while (
                incomingAcceptedAwaitingAck.get() &&
                    elapsedMs < SipIncomingInviteFinalResponses.maxFinalResponseRetransmitElapsedMs()
            ) {
                Thread.sleep(delayMs)
                elapsedMs += delayMs
                val stillSameCall = currentCall?.callIdOrNull() == acceptedCallId
                if (!incomingAcceptedAwaitingAck.get() || !stillSameCall) break
                Rlog.w(
                    TAG,
                    SipIncomingInviteFinalResponses.finalResponseRetransmitLog(
                        acceptedCallId = acceptedCallId,
                        elapsedMs = elapsedMs,
                    ),
                )
                val retransmitWriter =
                    currentCall?.takeIf { it.callIdOrNull() == acceptedCallId }?.incomingResponseWriter
                        ?: responseWriter
                if (
                    !writeSipBytes(
                        retransmitWriter,
                        responseBytes,
                        SipIncomingInviteFinalResponses.finalResponseRetransmitWriteContext(
                            acceptedCallId = acceptedCallId,
                            elapsedMs = elapsedMs,
                        ),
                    )
                ) {
                    Rlog.w(
                        TAG,
                        SipIncomingInviteFinalResponses.finalResponseRetransmitWriteFailureLog(
                            acceptedCallId = acceptedCallId,
                            elapsedMs = elapsedMs,
                        ),
                    )
                    incomingAcceptedAwaitingAck.set(false)
                    incomingHangupAfterAck.set(false)
                    break
                }
                delayMs = SipIncomingInviteFinalResponses.nextFinalResponseRetransmitDelayMs(delayMs)
            }
            if (incomingAcceptedAwaitingAck.get()) {
                Rlog.w(
                    TAG,
                    SipIncomingInviteFinalResponses.finalResponseMissingAckTimeoutLog(
                        acceptedCallId = acceptedCallId,
                        elapsedMs = elapsedMs,
                    ),
                )
                incomingAcceptedAwaitingAck.set(false)
                incomingHangupAfterAck.set(false)
                if (currentCall?.callIdOrNull() == acceptedCallId && !callStarted.get()) {
                    stopCallRuntime(SipIncomingInviteFinalResponses.finalResponseMissingAckStopReason())
                    rememberTerminatedIncomingCall(
                        acceptedCallId,
                        SipIncomingInviteFinalResponses.finalResponseMissingAckTerminationReason(),
                    )
                    currentCall = null
                    onCancelledCall?.invoke(
                        Object(),
                        "",
                        SipIncomingInviteFinalResponses.finalResponseMissingAckCancellationExtras(acceptedCallId),
                    )
                }
            }
        }
    }


    private data class AcceptedIncomingInviteFinalSdp(
        val call: Call,
        val omitFinalSdp: Boolean,
    )

    private fun prepareAcceptedIncomingInviteFinalSdp(
        call: Call,
        acceptedCallId: String,
    ): AcceptedIncomingInviteFinalSdp {
        val omitFinalSdp = SipIncomingInviteFinalResponses.shouldOmitFinalSdp(call.hasEarlyMedia)
        var acceptedCall = call
        if (!omitFinalSdp) {
            val finalIncomingSdp = completeIncomingPreconditionAnswerSdp(acceptedCall.sdp, acceptedCallId)
            if (!finalIncomingSdp.contentEquals(acceptedCall.sdp)) {
                acceptedCall = acceptedCall.copy(sdp = finalIncomingSdp)
                currentCall = acceptedCall
            }
        } else {
            Rlog.d(TAG, SipIncomingInviteFinalResponses.omitFinalSdpLogMessage(acceptedCallId))
        }

        return AcceptedIncomingInviteFinalSdp(
            call = acceptedCall,
            omitFinalSdp = omitFinalSdp,
        )
    }


    private data class IncomingAcceptTarget(
        val call: Call,
        val acceptedCallId: String,
    )

    private fun acceptedIncomingCallAfterAccessGuard(): IncomingAcceptTarget? {
        var call = currentCall
        if (call == null || call.outgoing) {
            Rlog.w(TAG, SipIncomingInviteFinalResponses.acceptWithoutValidIncomingCallLog(call))
            return null
        }

        val acceptedCallId = call.callIdOrEmpty()
        if (!delayIncomingAcceptAfterRecentImsAccessChange(acceptedCallId)) {
            return null
        }

        call = currentCall
        if (call == null || call.outgoing || call.callIdOrEmpty() != acceptedCallId) {
            Rlog.w(
                TAG,
                SipIncomingInviteFinalResponses.acceptAbortedAfterAccessGuardLog(
                    acceptedCallId = acceptedCallId,
                    currentCallId = call?.callIdOrEmpty(),
                    outgoing = call?.outgoing,
                ),
            )
            return null
        }

        return IncomingAcceptTarget(
            call = call,
            acceptedCallId = acceptedCallId,
        )
    }

    fun acceptCall() {
        thread {
            val acceptTarget = acceptedIncomingCallAfterAccessGuard() ?: return@thread
            var call = acceptTarget.call
            val acceptedCallId = acceptTarget.acceptedCallId

            // S9/O2 test mode: never block accept on pending incoming PRACK state.
            // The network currently does not PRACK our reliable incoming 183, so
            // waiting here makes the remote side ring until timeout.
            prAckWaitTracker.dropStaleBeforeAccept(TAG)

            Rlog.d(TAG, "Accepting call")
            val finalSdp = prepareAcceptedIncomingInviteFinalSdp(
                call = call,
                acceptedCallId = acceptedCallId,
            )
            call = finalSdp.call
            val omitFinalSdp = finalSdp.omitFinalSdp

            val msg3 = okAcceptedIncomingInviteFinalResponse(
                call = call,
                omitFinalSdp = omitFinalSdp,
            )
            val finalResponseWrite = sendAcceptedIncomingInviteFinalResponse(
                call = call,
                response = msg3,
                acceptedCallId = acceptedCallId,
            ) ?: return@thread
            val responseWriter = finalResponseWrite.responseWriter
            val responseBytes = finalResponseWrite.responseBytes
            prewarmIncomingMediaAfterAccept(call)

            startIncomingInviteFinalResponseRetransmit(
                acceptedCallId = acceptedCallId,
                responseWriter = responseWriter,
                responseBytes = responseBytes,
            )

            // Do not mark SIP confirmed here. For incoming calls, the dialog is only confirmed
            // when the remote side ACKs our 200 OK. handleAck() will set callStarted.
        }
    }

    fun prack(resp: SipResponse, cseq: Int) {
        val who = extractDestinationFromContact(resp.headers["contact"]!![0])
        val callId = resp.headers["call-id"]!![0]
        val rseq = resp.headers["rseq"]!![0]
        val whatToPrack = "$rseq ${resp.headers["cseq"]!![0]}"
        // PRACK is a request within the early dialog; route set comes from Record-Route
        // in the provisional response (RFC 3262 §4, RFC 3261 §12.1.2), not from the
        // registration Service-Route stored in commonHeaders.
        val dialogRoute = resp.headers["record-route"]
        val headers = if (dialogRoute != null) commonHeaders + ("route" to dialogRoute) else commonHeaders
        val msg =
            SipRequest(
                SipMethod.PRACK,
                who,
                headersParam = headers + """
                    RAck: $whatToPrack
                    CSeq: $cseq PRACK
                    Require: sec-agree
                    To: ${resp.headers["to"]!![0]}
                    From: ${resp.headers["from"]!![0]}
                    Call-Id: $callId
                    """.toSipHeadersMap()
            )
        Rlog.d(TAG, "Sending $msg")
        writeSipBytesWithFlush(socket.gWriter(), "SipHandler msg", msg.toByteArray())
    }

    fun rejectCall() {
        thread {
            val call = currentCall
            if (call == null || call.outgoing) {
                Rlog.w(TAG, SipIncomingInviteFinalResponses.rejectWithoutValidIncomingCallLog(call))
                return@thread
            }
            val rejectedCallId = call.callIdOrEmpty()
            rememberTerminatedIncomingCall(
                rejectedCallId,
                SipIncomingInviteFinalResponses.localRejectTerminationReason(),
            )
            val msg = SipIncomingInviteFinalResponses.localRejectResponse(call.callHeaders)
            val responseWriter = call.incomingResponseWriter ?: dispatcher.writerForCallId(rejectedCallId) ?: socket.gWriter()
            Rlog.d(
                TAG,
                SipIncomingInviteFinalResponses.localRejectWriteLog(
                    response = msg,
                    hasIncomingResponseWriter = call.incomingResponseWriter != null,
                ),
            )
            writeSipBytesWithFlush(responseWriter, "SipHandler msg", msg.toByteArray())

            stopCallRuntime(SipIncomingInviteFinalResponses.localRejectCleanupReason())
            incomingFinalResponseSent.set(false)
            incomingAcceptedAwaitingAck.set(false)
            incomingHangupAfterAck.set(false)
            currentCall = null
            onCancelledCall?.invoke(
                Object(),
                "",
                SipIncomingInviteFinalResponses.localRejectCancellationExtras(rejectedCallId),
            )
        }
    }

    private fun clearPendingOutgoingInvite(
        callId: String? = null,
        closeRtpSocket: Boolean = false,
        reason: String,
    ) {
        if (!reason.startsWith("final INVITE answer")) {
            callId?.let {
                outgoingConnectedCallIds.remove(it)
                outgoingConnectedDuplicateLogKeys.removeAll(
                    outgoingConnectedDuplicateLogKeys.filter { key -> key.startsWith("$it|") },
                )
            }
        }
        val pending = pendingOutgoingInvite ?: return
        if (callId != null && pending.callId != callId) return

        Rlog.d(
            TAG,
            SipRemoteDialogTermination.clearingPendingOutgoingInviteLog(
                callId = pending.callId,
                closeRtpSocket = closeRtpSocket,
                reason = reason,
            ),
        )
        pendingOutgoingInvite = null
        if (closeRtpSocket && currentCall?.rtpSocket !== pending.rtpSocket) {
            try {
                pending.rtpSocket.close()
            } catch (t: Throwable) {
                Rlog.d(TAG, SipRemoteDialogTermination.closingPendingOutgoingRtpSocketFailedLog(), t)
            }
        }
    }

    private fun sendCancelForPendingOutgoingInvite(pending: PendingOutgoingInvite, reason: String): Boolean {
        if (!pending.cancelSent.compareAndSet(false, true)) {
            Rlog.d(TAG, SipRemoteDialogTermination.pendingCancelAlreadySentLog(pending.callId, reason))
            return false
        }

        val inviteCseqNumber = SipRemoteDialogTermination.inviteCseqNumber(pending.headers)
        val cancellableHeaders = SipRemoteDialogTermination.cancellableCancelHeaders(pending.headers)
        val cancelHeaders = SipRemoteDialogTermination.cancelHeaders(
            cancellableHeaders = cancellableHeaders,
            inviteCseqNumber = inviteCseqNumber,
        )
        val cancel = SipRemoteDialogTermination.cancelRequest(
            destination = pending.destination,
            cancelHeaders = cancelHeaders,
        )
        Rlog.d(TAG, SipRemoteDialogTermination.pendingCancelSendLog(pending.callId, reason, cancel))
        writeSipBytesWithFlush(
            socket.gWriter(),
            SipRemoteDialogTermination.pendingCancelWriteLabel(),
            cancel.toByteArray(),
        )

        /*
         * Clear stale pending outgoing INVITE immediately after local CANCEL.
         *
         * Some carriers silently blackhole the originating INVITE. If the user
         * hangs up, no final INVITE response/487 may arrive, so keeping
         * pendingOutgoingInvite set poisons the next incoming call path.
         */
        clearPendingOutgoingInvite(
            callId = pending.callId,
            closeRtpSocket = true,
            reason = SipRemoteDialogTermination.localCancelSentReason(reason),
        )
        return true
    }

    private fun sendByeForCall(call: Call) {
        val byeHeaders = localDialogHeadersForRequest(call, SipMethod.BYE)
        val bye = SipRemoteDialogTermination.byeRequest(
            remoteContact = call.remoteContact,
            byeHeaders = byeHeaders,
        )
        Rlog.d(TAG, SipRemoteDialogTermination.byeLog(bye))
        writeSipBytesWithFlush(
            socket.gWriter(),
            SipRemoteDialogTermination.byeWriteLabel(),
            bye.toByteArray(),
        )
    }

    fun terminateCall() {
        val call = currentCall
        val pendingOutgoing = pendingOutgoingInvite

        if (call == null) {
            if (pendingOutgoing != null) {
                Rlog.w(TAG, SipRemoteDialogTermination.pendingOutgoingHangupLog(pendingOutgoing.callId))
                stopCallRuntime(SipRemoteDialogTermination.localTerminateReason())
                sendCancelForPendingOutgoingInvite(
                    pendingOutgoing,
                    SipRemoteDialogTermination.localHangupBeforeDialogReason(),
                )
                onCancelledCall?.invoke(
                    Object(),
                    "",
                    SipRemoteDialogTermination.localCancelExtras(pendingOutgoing.callId),
                )
                return
            }

            Rlog.w(TAG, SipRemoteDialogTermination.terminateWithoutCallLog())
            return
        }

        callStopped.set(true)

        if (call.outgoing && !callStarted.get()) {
            if (pendingOutgoing != null && pendingOutgoing.callId == call.callIdOrNull()) {
                Rlog.w(TAG, SipRemoteDialogTermination.localHangupBeforeFinalAnswerLog(pendingOutgoing.callId))
                sendCancelForPendingOutgoingInvite(
                    pendingOutgoing,
                    SipRemoteDialogTermination.localHangupBeforeFinalAnswerReason(),
                )
                currentCall = null
                onCancelledCall?.invoke(
                    Object(),
                    "",
                    SipRemoteDialogTermination.localCancelExtras(pendingOutgoing.callId),
                )
                return
            }
            Rlog.w(TAG, SipRemoteDialogTermination.outgoingUnconfirmedNoPendingInviteLog())
        }

        if (!call.outgoing && incomingFinalResponseSent.get() && !callStarted.get()) {
            Rlog.w(TAG, SipRemoteDialogTermination.incomingPreAckHangupLog())
            incomingHangupAfterAck.set(true)
            Rlog.d(TAG, SipRemoteDialogTermination.incomingPreAckKeepaliveLog())
            onCancelledCall?.invoke(
            Object(),
            "",
            SipRemoteDialogTermination.localHangupCancellationExtras(),
        )
            return
        }

        sendByeForCall(call)
        if (!call.outgoing) {
            rememberTerminatedIncomingCall(
                call.callIdOrEmpty(),
                SipRemoteDialogTermination.localByeTerminationReason(),
            )
            currentCall = null
        } else {
            val outgoingByeCallId = call.callIdOrNull()
            Rlog.d(TAG, SipRemoteDialogTermination.outgoingByeWaitLog(outgoingByeCallId))
            myHandler.postDelayed({
                if (currentCall?.outgoing == true &&
                    currentCall?.callIdOrNull() == outgoingByeCallId &&
                    callStopped.get()
                ) {
                    Rlog.w(TAG, SipRemoteDialogTermination.outgoingByeTimeoutLog(outgoingByeCallId))
                    currentCall = null
                }
            }, 4000L)
        }
        incomingAcceptedAwaitingAck.set(false)
        incomingHangupAfterAck.set(false)
        clearPendingOutgoingInvite(
            call.callIdOrNull(),
            closeRtpSocket = false,
            reason = SipRemoteDialogTermination.confirmedCallTerminatedReason(),
        )
        onCancelledCall?.invoke(
            Object(),
            "",
            SipRemoteDialogTermination.localHangupCancellationExtras(),
        )
    }

    /*
    Note: local/remote none/sendrecv are the precondition QoS status (RFC 3312).
    They signal that each side is pre-allocating media resources before the call is established.
    "none" = not yet ready, "sendrecv" = ready to send and receive.

    Outgoing call process — all messages are local→remote unless noted otherwise.
    This callback (setResponseCallback on the INVITE call-id) handles responses to our
    INVITE and to in-dialog requests we send (PRACK, UPDATE).  Incoming requests from the
    remote (e.g. the remote's UPDATE in step 8) are handled separately in parseMessage.

    1. Send INVITE with SDP:
         a=curr:qos local none   (we haven't allocated media yet)
         a=curr:qos remote none  (remote hasn't either)
         a=des:qos optional local/remote sendrecv
         Lists all tracks we support (AMR, DTMF).

    2. Receive 100 Trying — ignored (no SDP → return false).

    3. Receive 183 Session Progress with remote SDP (track selected) and RSeq header.
       → Send PRACK for that RSeq, save 183 as respInFlight, return false (suspend processing).

    4. Receive 200 OK PRACK — resume processing the saved 183 (rseqHandled=true).
       Two sub-paths depending on whether the 183 carried Require: precondition:

       Path A — precondition present, local=none:
         → Start callDecodeThread + callEncodeThread (encoder sends silence, mic not open yet).
         → Send UPDATE claiming local=sendrecv (we have allocated our media resources).

       Path B — no precondition (or precondition already satisfied):
         → Start callDecodeThread + callEncodeThread immediately.
         → No UPDATE sent; proceed to wait for 180/200.

    5. [Path A] Receive 200 OK UPDATE — remote now reports sendrecv on both sides.
       Nothing to do in code; currentCall SDP was already updated when 200 arrived.

    6. [Path A] Receive another 183 Session Progress (no SDP, no new RSeq — no PRACK needed).
       → !isSdp → return false.

    7. [Handled in parseMessage, not here] Remote sends UPDATE with its final SDP.
       We respond 200 OK with our SDP.

    8. Receive 180 Ringing — no SDP → return false (just informs UI via onOutgoingCallConnected
       which is only fired on 200 OK, not here).

    9. Receive 200 OK on INVITE — call is accepted:
       → Send ACK (ACK to 2xx goes to Contact URI, routed via Record-Route; no response to ACK).
       → callStarted.set(true): encode thread exits silence loop, AudioRecord opens (mic live).
       → onOutgoingCallConnected invoked.

    Call is now running.

    Session timers (RFC 4028): we advertise Session-Expires: 900 / Supported: timer.
    The network nominates a refresher; if it nominates us (UAC), we must send a re-INVITE
    before the session expires. If it nominates itself (UAS), it sends re-INVITEs to us and
    we respond 200 OK (handled in parseMessage as an incoming INVITE).
    NOTE: periodic re-INVITE sending is not yet implemented for the UAC-refresher case.
     */

    var respInFlight: SipResponse? = null

    /*
     * SingTel outgoing INVITE handling.
     *
     * SingTel accepts registration and IMS SMS, but silently drops oversized
     * protected originating MMTEL INVITEs before 100 Trying on the LTE IMS path.
     * Keep the special request shape scoped to SingTel.
     */
    private fun isSingTelStockOutgoingCarrier(): Boolean =
        (mcc == "525" && (mnc == "001" || mnc == "01")) ||
            realm.equals("ims.mnc001.mcc525.3gppnetwork.org", ignoreCase = true) ||
            registerTargetRealm.equals("ims.singtel.com", ignoreCase = true)

    private fun singtelStockLocalNumberForPhoneContext(number: String): String {
        val digits = number.trim().trimStart('+')
        return if (digits.startsWith("65") && digits.length == 10) {
            digits.substring(2)
        } else {
            digits
        }
    }

    private fun singtelPublicSipUri(number: String): String {
        val digits = singtelStockLocalNumberForPhoneContext(number)
        val e164 = if (digits.startsWith("+") || digits.startsWith("65")) {
            if (digits.startsWith("+")) digits else "+$digits"
        } else {
            "+65$digits"
        }
        return "sip:$e164@ims.singtel.com"
    }

    private fun createOutgoingCallRtpSocket(): DatagramSocket? {
        val rtpSocket = try {
            DatagramSocket(0, localAddr)
        } catch (t: Throwable) {
            val staleReason = "outgoing RTP bind failed for localAddr=$localAddr"
            Rlog.e(TAG, "Failed to bind outgoing RTP socket to $localAddr; IMS address is likely stale", t)
            reconnectIms(staleReason)
            onCancelledCall?.invoke(
                Object(),
                "",
                mapOf(
                    "statusCode" to "480",
                    "statusString" to "Stale IMS transport",
                    "localImsAddressStale" to "true",
                ),
            )
            return null
        }
        try {
            network.bindSocket(rtpSocket)
        } catch (t: Throwable) {
            Rlog.e(TAG, "Failed to bind outgoing RTP socket to IMS network", t)
            try { rtpSocket.close() } catch (_: Throwable) {}
            reconnectIms("outgoing RTP network.bindSocket failed")
            return null
        }
        rtpSocket.soTimeout = RTP_SOCKET_RECEIVE_TIMEOUT_MS
        // Connect later once the remote RTP address/port is known from SDP.
        Rlog.d(TAG, "RTP socket created for outgoing call: local=${rtpSocket.localAddress}:${rtpSocket.localPort} timeout=${rtpSocket.soTimeout}")
        return rtpSocket
    }


    private fun buildOutgoingInviteSdpOffer(
        rtpSocket: DatagramSocket,
    ): OutgoingInviteSdpOffer {
        return SipOutgoingInviteSdp.build(
            logTag = TAG,
            rtpSocket = rtpSocket,
            localHost = "${socket.gLocalAddr().hostAddress}",
            ipType = if (localAddr is Inet6Address) "IP6" else "IP4",
            amrWbMediaCodecAvailable = amrWbMediaCodecAvailable,
            singtelStockOutgoingCarrier = isSingTelStockOutgoingCarrier(),
        )
    }


    private fun buildOutgoingInviteRequest(
        phoneNumber: String,
        outgoingInviteBody: ByteArray,
    ): OutgoingInviteRequestContext {
        val normalizedPhoneNumber = normalizeOutgoingDialTargetForTelUri(phoneNumber)
        val localEndpoint =
            if(socket.gLocalAddr() is Inet6Address)
                "[${socket.gLocalAddr().hostAddress}]:${serverSocket.localPort}"
            else
                "${socket.gLocalAddr().hostAddress}:${serverSocket.localPort}"
        val transport = if (socket is SipConnectionTcp) "tcp" else "udp"
        val outgoingInviteSessionTimer = inviteSessionTimerPolicy.currentForRealm(realm)

        return SipOutgoingInviteRequestBuilder.build(
            logTag = TAG,
            phoneNumber = phoneNumber,
            outgoingInviteBody = outgoingInviteBody,
            normalizedPhoneNumber = normalizedPhoneNumber,
            mcc = mcc,
            mnc = mnc,
            registrationTech = imsRegistrationTech,
            mySip = mySip,
            myTel = myTel,
            imsi = imsi,
            imei = imei,
            commonHeaders = commonHeaders,
            localEndpoint = localEndpoint,
            transport = transport,
            sessionExpiresSeconds = outgoingInviteSessionTimer.sessionExpiresSeconds,
            minSeSeconds = outgoingInviteSessionTimer.minSeSeconds,
            generatedCallIdHeaders = generateCallId(),
            singtelStockOutgoingCarrier = isSingTelStockOutgoingCarrier(),
            singtelPublicSipUri = { number -> singtelPublicSipUri(number) },
        )
    }


    private fun shouldIgnoreStaleOutgoingResponse(
        response: SipResponse,
        expectedCallId: String,
    ): Boolean {
        return SipOutgoingInviteResponseGuards.shouldIgnoreStaleResponse(
            logTag = TAG,
            response = response,
            expectedCallId = expectedCallId,
            activeCallIdForResponse = currentCall?.callIdOrNull(),
            pendingCallIdForResponse = pendingOutgoingInvite?.callId,
        )
    }


    private fun handleOutgoingAckOrByeResponse(
        response: SipResponse,
        cseq: String,
    ): Boolean? {
        val result = SipOutgoingInviteAckByeResponses.handle(
            logTag = TAG,
            response = response,
            cseq = cseq,
        ) ?: return null

        val clearDialogCallId = result.clearDialogCallId
        if (clearDialogCallId != null) {
            currentCall = null
            clearPendingOutgoingInvite(
                clearDialogCallId,
                closeRtpSocket = false,
                reason = result.clearPendingReason
                    ?: SipOutgoingInviteAckByeResponses.fallbackByeResponseCleanupReason(
                        cseq = cseq,
                        statusCode = response.statusCode,
                    ),
            )
        }
        return result.callbackResult
    }


    private fun handleOutgoingPrackResponseIfNeeded(
        response: SipResponse,
        cseq: String,
        prackedReliableProvisionals: MutableSet<String>,
    ): OutgoingPrackResponseState {
        val state = SipOutgoingInvitePrackResponses.handle(
            logTag = TAG,
            response = response,
            cseq = cseq,
            prackedReliableProvisionals = prackedReliableProvisionals,
            savedProvisional = respInFlight,
        )
        if (state.clearRespInFlight) {
            respInFlight = null
        }
        return state
    }


    private fun buildOutgoingFinalInviteAckRequest(
        response: SipResponse,
        myHeaders: Map<String, List<String>>,
        to: String,
    ): OutgoingFinalInviteAckRequest {
        return SipOutgoingInviteFinalAck.buildAckRequest(
            response = response,
            myHeaders = myHeaders,
            fallbackTarget = to,
            extractDestinationFromContact = { contact -> extractDestinationFromContact(contact) },
        )
    }


    private fun updateOutgoingDialogAfterFinalInviteAck(
        response: SipResponse,
        inviteCseq: Int,
        outgoingDialogNextCseq: AtomicInteger,
    ) {
        // Update dialog route set from the confirmed 200 OK (RFC 3261 §12.1.2)
        // so that subsequent in-dialog requests (BYE, UPDATE) use the correct route.
        val rrFrom200Ok = response.headers["record-route"]
        val remoteTargetFrom200Ok = response.headers["contact"]?.getOrNull(0)
            ?.let { extractDestinationFromContact(it) }
        currentCall = currentCall?.let { confirmedCall ->
            var confirmedHeaders = confirmedCall.callHeaders
            if (rrFrom200Ok != null) {
                confirmedHeaders = confirmedHeaders + ("record-route" to rrFrom200Ok) + ("route" to rrFrom200Ok)
            }
            // INVITE uses its original CSeq for ACK. Keep later in-dialog requests
            // past any PRACK/UPDATE/BYE CSeq already allocated while the call was pending.
            val nextDialogCseq = maxOf(inviteCseq + 1, outgoingDialogNextCseq.get())
            val keptCseq = maxOf(confirmedCall.localCseq.get(), nextDialogCseq)
            confirmedCall.copy(
                callHeaders = confirmedHeaders,
                remoteContact = remoteTargetFrom200Ok ?: confirmedCall.remoteContact,
                localCseq = AtomicInteger(keptCseq),
            )
        }
        currentCall?.let { confirmedCall ->
            val routeHeader = confirmedCall.callHeaders["route"]
            Rlog.d(
                TAG,
                SipOutgoingInviteFinalAckState.outgoingConfirmedDialogAfterAckLog(
                    remoteTarget = confirmedCall.remoteContact,
                    nextLocalCseq = confirmedCall.localCseq.get(),
                    routeHeader = routeHeader,
                ),
            )
        }
    }


    private fun handleOutgoingFinalInvitePostAckState(
        finalInviteCallId: String,
        finalInviteAfterLocalCancel: Boolean,
        finalInviteHasSdp: Boolean,
    ): Boolean? {
        if (finalInviteAfterLocalCancel) {
            Rlog.w(TAG, SipOutgoingInviteFinalAckState.finalInviteAfterLocalCancelWithoutSdpLog(finalInviteCallId))
            currentCall?.let { sendByeForCall(it) }
            currentCall = null
            clearPendingOutgoingInvite(
                finalInviteCallId,
                closeRtpSocket = true,
                reason = SipOutgoingInviteFinalAckState.finalAnswerWithoutSdpAfterLocalCancelReason(),
            )
            return true
        } else if (!finalInviteHasSdp) {
            clearPendingOutgoingInvite(
            finalInviteCallId,
            closeRtpSocket = false,
            reason = SipOutgoingInviteFinalAckState.finalInviteAnswerWithoutSdpReason(),
        )
            val confirmedOutgoingCall = currentCall
            if (confirmedOutgoingCall != null) {
                confirmedOutgoingCall.outgoingRtpReceived.set(false)
                Rlog.d(TAG, SipOutgoingInviteFinalAckState.finalInviteAnswerClearsEarlyMediaGateLog(finalInviteCallId))
                scheduleOutgoingPostAnswerRtpTimeout(finalInviteCallId)
                maybeNotifyOutgoingCallConnected(
                    confirmedOutgoingCall,
                    SipOutgoingInviteFinalAckState.finalInviteAnswerConnectedReason(),
                )
            } else {
                Rlog.w(TAG, SipOutgoingInviteFinalAckState.finalInviteAnswerCurrentCallMissingLog(finalInviteCallId))
            }
        }
        return null
    }


    private fun outgoingFinalInviteAckState(response: SipResponse): OutgoingFinalInviteAckState? {
        val finalInviteCallId = response.callIdOrEmpty()
        val finalInviteAfterLocalCancel = pendingOutgoingInvite?.callId == finalInviteCallId &&
            pendingOutgoingInvite?.cancelSent?.get() == true

        return SipOutgoingInviteFinalAckState.fromResponse(
            logTag = TAG,
            response = response,
            finalInviteAfterLocalCancel = finalInviteAfterLocalCancel,
        )
    }


    private fun handleOutgoingFinalInviteAckIfNeeded(
        response: SipResponse,
        myHeaders: Map<String, List<String>>,
        outgoingDialogNextCseq: AtomicInteger,
        to: String,
    ): Boolean? {
        val outgoingFinalInviteAckState = outgoingFinalInviteAckState(response) ?: return null
        val finalInviteCallId = outgoingFinalInviteAckState.finalInviteCallId
        val finalInviteAfterLocalCancel = outgoingFinalInviteAckState.finalInviteAfterLocalCancel
        val finalInviteHasSdp = outgoingFinalInviteAckState.finalInviteHasSdp
        val outgoingFinalInviteAckRequest = buildOutgoingFinalInviteAckRequest(
            response = response,
            myHeaders = myHeaders,
            to = to,
        )
        val msg2 = outgoingFinalInviteAckRequest.request
        val cseq = outgoingFinalInviteAckRequest.inviteCseq
        Rlog.d(TAG, SipOutgoingInviteFinalAckState.sendingFinalInviteAckLog(msg2))
        synchronized(socket.gWriter()) { socket.gWriter().write(msg2.toByteArray()); socket.gWriter().flush() }
        callStarted.set(true)
        updateOutgoingDialogAfterFinalInviteAck(
            response = response,
            inviteCseq = cseq,
            outgoingDialogNextCseq = outgoingDialogNextCseq,
        )
        return handleOutgoingFinalInvitePostAckState(
            finalInviteCallId = finalInviteCallId,
            finalInviteAfterLocalCancel = finalInviteAfterLocalCancel,
            finalInviteHasSdp = finalInviteHasSdp,
        )
    }


    private fun handleOutgoingProgressOrFailureResponse(
        response: SipResponse,
        cseq: String,
        outgoingDialogNextCseq: AtomicInteger,
    ): Boolean? {
        if (cseq.contains("INVITE") && (response.statusCode == 200 || response.statusCode == 202)) {
            return null
        }

        SipOutgoingInviteProgressResponses.progressNotification(
            logTag = TAG,
            response = response,
        )?.let { progress ->
            onOutgoingCallProgressing?.invoke(Object(), progress.extras)
        }

        if(response.statusCode >= 400) {
            val failedPendingInvite = pendingOutgoingInvite
            if (failedPendingInvite != null &&
                failedPendingInvite.callId == response.callIdOrEmpty() &&
                retryOutgoingInviteAfter422(failedPendingInvite, response, outgoingDialogNextCseq)
            ) {
                return false
            }
            val failedCallId = response.callIdOrEmpty()
            val failedCseq = response.headers["cseq"]?.getOrNull(0).orEmpty()
            val activeCallId = currentCall?.callIdOrNull()
            val pendingCallId = pendingOutgoingInvite?.callId

            if (activeCallId != failedCallId && pendingCallId != failedCallId) {
                Rlog.w(
                    TAG,
                    SipOutgoingInviteProgressResponses.staleOutgoingDialogFailureLog(
                        response = response,
                        failedCseq = failedCseq,
                        failedCallId = failedCallId,
                        activeCallId = activeCallId,
                        pendingCallId = pendingCallId,
                    ),
                )
                return true
            }

            if (failedPendingInvite != null &&
                failedPendingInvite.callId == failedCallId &&
                failedCseq.contains("INVITE", ignoreCase = true) &&
                retryOutgoingInviteAfterIllegalSdp(failedPendingInvite, response, outgoingDialogNextCseq)
            ) {
                return false
            }

            Rlog.w(
                TAG,
                SipOutgoingInviteProgressResponses.outgoingDialogRequestFailedLog(
                    response = response,
                    failedCseq = failedCseq,
                    failedCallId = failedCallId,
                ),
            )
            stopCallRuntime(SipOutgoingInviteProgressResponses.outgoingDialogFailureCleanupReason())

            val failedPending = pendingOutgoingInvite
            if (failedPending != null && failedPending.callId == failedCallId &&
                !failedCseq.contains("INVITE") && !failedPending.cancelSent.get()) {
                Rlog.w(TAG, SipOutgoingInviteProgressResponses.earlyOutgoingInDialogRequestFailedLog(failedCallId))
                sendCancelForPendingOutgoingInvite(
                    failedPending,
                    SipOutgoingInviteProgressResponses.earlyDialogRequestFailedCancelReason(
                        failedCseq = failedCseq,
                        statusCode = response.statusCode,
                    ),
                )
            }

            if (activeCallId == failedCallId) {
                currentCall = null
            }
            clearPendingOutgoingInvite(
                failedCallId,
                closeRtpSocket = activeCallId != failedCallId,
                reason = SipOutgoingInviteProgressResponses.outgoingDialogFailurePendingCleanupReason(
                    failedCseq = failedCseq,
                    statusCode = response.statusCode,
                ),
            )
            onCancelledCall?.invoke(
                Object(),
                "",
                SipOutgoingInviteProgressResponses.outgoingDialogFailureCancellationExtras(
                    response = response,
                    failedCseq = failedCseq,
                ),
            )
            // The whole call failed, so drop that call-id
            return true
        }

        return null
    }


    private fun handleOutgoingReliableProvisionalIfNeeded(
        response: SipResponse,
        rseqHandled: Boolean,
        outgoingDialogNextCseq: AtomicInteger,
        prackedReliableProvisionals: MutableSet<String>,
    ): Boolean? {
        val reliableProvisional = SipOutgoingInviteReliableProvisionals.classify(
            logTag = TAG,
            response = response,
            rseqHandled = rseqHandled,
            prackedReliableProvisionals = prackedReliableProvisionals,
        ) ?: return null
        reliableProvisional.callbackResult?.let { return it }
        val reliableKey = reliableProvisional.reliableKey
        val currentCallNextCseq = currentCall?.localCseq?.get() ?: 0
        val allocatorNextCseq = outgoingDialogNextCseq.get()
        if (currentCallNextCseq > allocatorNextCseq) {
            Rlog.d(
                TAG,
                SipOutgoingInviteReliableProvisionals.syncingPrackCseqAllocatorLog(
                    allocatorNextCseq = allocatorNextCseq,
                    currentCallNextCseq = currentCallNextCseq,
                    reliableKey = reliableKey,
                ),
            )
            outgoingDialogNextCseq.set(currentCallNextCseq)
        }
        val prackCseq = outgoingDialogNextCseq.getAndIncrement()
        currentCall?.localCseq?.let { callCseq ->
            while (true) {
                val old = callCseq.get()
                val desired = prackCseq + 1
                if (old >= desired || callCseq.compareAndSet(old, desired)) break
            }
        }
        prack(response, prackCseq)
        Rlog.d(
            TAG,
            SipOutgoingInviteReliableProvisionals.prackConsumedLocalCseqLog(
                prackCseq = prackCseq,
                nextAllocatorCseq = outgoingDialogNextCseq.get(),
                currentCallNextCseq = currentCall?.localCseq?.get(),
                reliableKey = reliableKey,
            ),
        )
        respInFlight = response
        return false
    }


    private fun parseOutgoingDialogSdpAnswer(
        response: SipResponse,
        rtpSocket: DatagramSocket,
        amrNbTrack: Int,
        dtmfNbTrack: Int,
    ): OutgoingDialogSdpAnswer? =
        SipOutgoingDialogSdp.parseAnswer(
            logTag = TAG,
            response = response,
            rtpSocket = rtpSocket,
            amrNbTrack = amrNbTrack,
            dtmfNbTrack = dtmfNbTrack,
            amrWbMediaCodecAvailable = amrWbMediaCodecAvailable,
        )


    private fun buildOutgoingDialogCallFromSdpAnswer(
        response: SipResponse,
        myHeaders: Map<String, List<String>>,
        rtpSocket: DatagramSocket,
        answer: OutgoingDialogSdpAnswer,
        nextLocalCseqForDialog: Int,
    ): Call =
        Call(
            outgoing = true,
            audioCodec = answer.dialogAudioCodec,
            amrTrack = answer.dialogAmrTrack,
            amrTrackDesc = answer.dialogAmrTrackDesc,
            dtmfTrack = answer.dialogDtmfTrack,
            dtmfTrackDesc = answer.dialogDtmfTrackDesc,
            // Update from/to/call-id based on the response we got to include the remote tag.
            // Keep the response Record-Route too; later local BYE/UPDATE must use it as Route.
            callHeaders = myHeaders - "require" - "content-type" +
                ("from" to response.headers["from"]!!) +
                ("to" to response.headers["to"]!!) +
                ("call-id" to response.headers["call-id"]!!) +
                (response.headers["record-route"]?.let { mapOf("record-route" to it, "route" to it) } ?: emptyMap()),
            rtpRemoteAddr = answer.rtpRemoteAddr,
            rtpRemotePort = answer.rtpRemotePortInt,
            rtpSocket = rtpSocket,
            sdp = response.body,
            hasEarlyMedia = response.headers["p-early-media"]?.isNotEmpty() == true,
            remoteContact = extractDestinationFromContact(response.headers["contact"]!![0]),
            localCseq = AtomicInteger(nextLocalCseqForDialog),
        )


    private fun logOutgoingDialogSdpInstall(
        response: SipResponse,
        responseCseq: String,
    ) {
        val outgoingDialogCallForLog = currentCall
        Rlog.d(
            TAG,
            SipOutgoingDialogSdp.installLogMessage(
                response = response,
                responseCseq = responseCseq,
                audioCodec = outgoingDialogCallForLog?.audioCodec,
                amrTrack = outgoingDialogCallForLog?.amrTrack,
                dtmfTrack = outgoingDialogCallForLog?.dtmfTrack,
                remoteTarget = outgoingDialogCallForLog?.remoteContact,
                nextLocalCseq = outgoingDialogCallForLog?.localCseq?.get(),
                route = outgoingDialogCallForLog?.callHeaders?.get("route"),
            ),
        )
    }


    private fun checkOutgoingFinalInviteMediaFormatChanged(
        response: SipResponse,
        responseCseq: String,
        previousOutgoingDialogCall: Call?,
        answer: OutgoingDialogSdpAnswer,
    ): Boolean =
        SipOutgoingDialogSdp.finalInviteMediaFormatChanged(
            logTag = TAG,
            threadsStarted = threadsStarted.get(),
            response = response,
            responseCseq = responseCseq,
            previousDialogPresent = previousOutgoingDialogCall != null,
            previousAudioCodec = previousOutgoingDialogCall?.audioCodec,
            previousAmrTrack = previousOutgoingDialogCall?.amrTrack,
            previousDtmfTrack = previousOutgoingDialogCall?.dtmfTrack,
            previousRtpRemoteAddr = previousOutgoingDialogCall?.rtpRemoteAddr,
            previousRtpRemotePort = previousOutgoingDialogCall?.rtpRemotePort,
            answer = answer,
        )


    private fun installOutgoingDialogFromSdpAnswer(
        response: SipResponse,
        outgoingDialogNextCseq: AtomicInteger,
        myHeaders: Map<String, List<String>>,
        rtpSocket: DatagramSocket,
        answer: OutgoingDialogSdpAnswer,
    ): OutgoingDialogSdpInstallResult {
        val nextLocalCseqForDialog = SipOutgoingDialogSdp.nextLocalCseqForDialog(
            response = response,
            outgoingDialogNextCseq = outgoingDialogNextCseq.get(),
            currentCallLocalCseq = currentCall?.localCseq?.get() ?: 0,
        )
        // PhhIms: final INVITE SDP media restart.
        // Keep the media state selected by a provisional 183 before replacing
        // currentCall with the later/final SDP answer. Some IMS cores answer
        // 183 with AMR-NB and then switch the confirmed 200 OK to AMR-WB.
        val previousOutgoingDialogCall = currentCall
        currentCall = buildOutgoingDialogCallFromSdpAnswer(
            response = response,
            myHeaders = myHeaders,
            rtpSocket = rtpSocket,
            answer = answer,
            nextLocalCseqForDialog = nextLocalCseqForDialog,
        )
        restartOutgoingMediaAfterDialogSdpCodecChange(
            previousOutgoingDialogCall,
            currentCall,
            "status=${response.statusCode} cseq=${response.headers["cseq"]?.getOrNull(0).orEmpty()}",
        )
        val responseCseq = response.headers["cseq"]?.getOrNull(0).orEmpty()
        logOutgoingDialogSdpInstall(
            response = response,
            responseCseq = responseCseq,
        )

        val outgoingMediaFormatChanged = checkOutgoingFinalInviteMediaFormatChanged(
            response = response,
            responseCseq = responseCseq,
            previousOutgoingDialogCall = previousOutgoingDialogCall,
            answer = answer,
        )

        return OutgoingDialogSdpInstallResult(
            responseCseq = responseCseq,
            outgoingMediaFormatChanged = outgoingMediaFormatChanged,
        )
    }


    private fun handleOutgoingFinalInviteSdpMedia(
        response: SipResponse,
        responseCseq: String,
        outgoingMediaFormatChanged: Boolean,
        answer: OutgoingDialogSdpAnswer,
    ): Boolean? {
        val responseCallId = response.callIdOrEmpty()
        val finalInviteAfterLocalCancel = pendingOutgoingInvite?.callId == responseCallId &&
            pendingOutgoingInvite?.cancelSent?.get() == true
        val finalInviteSdpMediaState = SipOutgoingDialogSdp.finalInviteSdpMediaState(
            logTag = TAG,
            response = response,
            responseCseq = responseCseq,
            finalInviteAfterLocalCancel = finalInviteAfterLocalCancel,
        ) ?: return null

        val finalInviteCallId = finalInviteSdpMediaState.finalInviteCallId
        if (finalInviteSdpMediaState.finalInviteAfterLocalCancel) {
            currentCall?.let { sendByeForCall(it) }
            currentCall = null
            clearPendingOutgoingInvite(
                finalInviteCallId,
                closeRtpSocket = true,
                reason = SipOutgoingDialogSdp.finalAnswerAfterLocalCancelReason(),
            )
            return true
        }

        clearPendingOutgoingInvite(
            finalInviteCallId,
            closeRtpSocket = false,
            reason = SipOutgoingDialogSdp.finalInviteAnswerReason(),
        )
        val finalInviteMediaAction = SipOutgoingDialogSdp.finalInviteMediaThreadAction(
            startedNow = threadsStarted.compareAndSet(false, true),
            outgoingMediaFormatChanged = outgoingMediaFormatChanged,
        )
        when (finalInviteMediaAction) {
            OutgoingFinalInviteMediaThreadAction.START -> {
                Rlog.d(
                    TAG,
                    SipOutgoingDialogSdp.finalInviteMediaThreadLogMessage(
                        action = finalInviteMediaAction,
                        mediaRestartGeneration = null,
                        answer = answer,
                    ),
                )
                callDecodeThread()
                callEncodeThread()
            }
            OutgoingFinalInviteMediaThreadAction.RESTART -> {
                val mediaRestartGeneration = callGeneration.incrementAndGet()
                Rlog.w(
                    TAG,
                    SipOutgoingDialogSdp.finalInviteMediaThreadLogMessage(
                        action = finalInviteMediaAction,
                        mediaRestartGeneration = mediaRestartGeneration,
                        answer = answer,
                    ),
                )
                callDecodeThread()
                callEncodeThread()
            }
            OutgoingFinalInviteMediaThreadAction.ALREADY_STARTED -> {
                Rlog.d(
                    TAG,
                    SipOutgoingDialogSdp.finalInviteMediaThreadLogMessage(
                        action = finalInviteMediaAction,
                        mediaRestartGeneration = null,
                        answer = answer,
                    ),
                )
            }
        }
        return false
    }


    private fun handleOutgoingPrecondition183IfNeeded(
        response: SipResponse,
        isPrecondition: Boolean,
        respSdp: List<String>,
        originalInviteSdp: ByteArray,
        fallbackTarget: String,
    ): Boolean? {
        if (!isPrecondition || response.statusCode != 183) return null

        val preconditionState = SipOutgoingDialogSdp.precondition183State(
            logTag = TAG,
            respSdp = respSdp,
        )
        val remoteHasLocalQos = preconditionState.remoteHasLocalQos
        val needsLocalQosUpdate = preconditionState.needsLocalQosUpdate
        if (needsLocalQosUpdate) {
            // "Allocating our local resource" and update the call
            if (threadsStarted.compareAndSet(false, true)) {
                Rlog.d(TAG, SipOutgoingDialogSdp.startingOutgoingMediaFromPrecondition183SdpLog())
                callDecodeThread()
                callEncodeThread()
            }

            val newSdp = SipOutgoingDialogSdp.buildPreconditionUpdateSdp(
                originalInviteSdp = originalInviteSdp,
                respSdp = respSdp,
                remoteHasLocalQos = remoteHasLocalQos,
                nextLocalSdpVersion = { currentCall?.localSdpVersion?.incrementAndGet() ?: 3 },
            )

            val updateHeaders = localDialogHeadersForRequest(currentCall!!, SipMethod.UPDATE) -
                "content-length" +
                ("content-type" to listOf("application/sdp"))

            val msg2 = SipOutgoingDialogSdp.buildPreconditionUpdateRequest(
                remoteContact = currentCall!!.remoteContact,
                fallbackTarget = fallbackTarget,
                updateHeaders = updateHeaders,
                newSdp = newSdp,
            )
            Rlog.d(TAG, SipOutgoingDialogSdp.sendingPreconditionUpdateLog(msg2))
            writeSipBytesWithFlush(
                socket.gWriter(),
                SipOutgoingDialogSdp.preconditionUpdateWriteLabel(),
                msg2.toByteArray(),
            )
        }

        return false
    }


    private fun startOutgoingMediaForNonPrecondition183IfNeeded(
        response: SipResponse,
        isPrecondition: Boolean,
    ) {
        if (!SipOutgoingDialogSdp.shouldStartMediaForNonPrecondition183(
                response = response,
                isPrecondition = isPrecondition,
            )
        ) return

        if (threadsStarted.compareAndSet(false, true)) {
            Rlog.d(TAG, SipOutgoingDialogSdp.startingOutgoingMediaFromNonPrecondition183SdpLog())
            callDecodeThread()
            callEncodeThread()
        }
    }


    private fun handleOutgoingUpdateSdpResponseIfNeeded(
        response: SipResponse,
    ): Boolean? =
        SipOutgoingInviteUpdateResponses.handleIfNeeded(response)


    private fun handleOutgoingDialogSdpResponse(
        response: SipResponse,
        rtpSocket: DatagramSocket,
        amrNbTrack: Int,
        dtmfNbTrack: Int,
        outgoingDialogNextCseq: AtomicInteger,
        myHeaders: Map<String, List<String>>,
        originalInviteSdp: ByteArray,
        fallbackTarget: String,
    ): Boolean {
        val outgoingDialogSdpAnswer = parseOutgoingDialogSdpAnswer(
            response = response,
            rtpSocket = rtpSocket,
            amrNbTrack = amrNbTrack,
            dtmfNbTrack = dtmfNbTrack,
        ) ?: return false
        val isPrecondition = outgoingDialogSdpAnswer.isPrecondition
        val respSdp = outgoingDialogSdpAnswer.respSdp

        val outgoingDialogSdpInstall = installOutgoingDialogFromSdpAnswer(
            response = response,
            outgoingDialogNextCseq = outgoingDialogNextCseq,
            myHeaders = myHeaders,
            rtpSocket = rtpSocket,
            answer = outgoingDialogSdpAnswer,
        )
        val responseCseq = outgoingDialogSdpInstall.responseCseq
        val outgoingMediaFormatChanged = outgoingDialogSdpInstall.outgoingMediaFormatChanged
        handleOutgoingFinalInviteSdpMedia(
            response = response,
            responseCseq = responseCseq,
            outgoingMediaFormatChanged = outgoingMediaFormatChanged,
            answer = outgoingDialogSdpAnswer,
        )?.let { return it }

        handleOutgoingUpdateSdpResponseIfNeeded(response)?.let { return it }

        handleOutgoingPrecondition183IfNeeded(
            response = response,
            isPrecondition = isPrecondition,
            respSdp = respSdp,
            originalInviteSdp = originalInviteSdp,
            fallbackTarget = fallbackTarget,
        )?.let { return it }

        startOutgoingMediaForNonPrecondition183IfNeeded(
            response = response,
            isPrecondition = isPrecondition,
        )

        return false
    }


    private fun handleOutgoingInviteResponse(
        response: SipResponse,
        outgoingInviteCallId: String,
        prackedReliableProvisionals: MutableSet<String>,
        outgoingDialogNextCseq: AtomicInteger,
        myHeaders: Map<String, List<String>>,
        rtpSocket: DatagramSocket,
        amrNbTrack: Int,
        dtmfNbTrack: Int,
        to: String,
        sdp: ByteArray,
    ): Boolean {
        if (shouldIgnoreStaleOutgoingResponse(
                response = response,
                expectedCallId = outgoingInviteCallId,
            )
        ) return true

        var resp = response
        var cseq = resp.headers["cseq"]!![0]

        val prackResponseState = handleOutgoingPrackResponseIfNeeded(
            response = resp,
            cseq = cseq,
            prackedReliableProvisionals = prackedReliableProvisionals,
        )
        prackResponseState.callbackResult?.let { return it }
        resp = prackResponseState.response
        cseq = prackResponseState.cseq
        val rseqHandled = prackResponseState.rseqHandled

        handleOutgoingAckOrByeResponse(resp, cseq)?.let { return it }

        handleOutgoingFinalInviteAckIfNeeded(
            response = resp,
            myHeaders = myHeaders,
            outgoingDialogNextCseq = outgoingDialogNextCseq,
            to = to,
        )?.let { return it }

        handleOutgoingProgressOrFailureResponse(
            response = resp,
            cseq = cseq,
            outgoingDialogNextCseq = outgoingDialogNextCseq,
        )?.let { return it }

        handleOutgoingReliableProvisionalIfNeeded(
            response = resp,
            rseqHandled = rseqHandled,
            outgoingDialogNextCseq = outgoingDialogNextCseq,
            prackedReliableProvisionals = prackedReliableProvisionals,
        )?.let { return it }

        return handleOutgoingDialogSdpResponse(
            response = resp,
            rtpSocket = rtpSocket,
            amrNbTrack = amrNbTrack,
            dtmfNbTrack = dtmfNbTrack,
            outgoingDialogNextCseq = outgoingDialogNextCseq,
            myHeaders = myHeaders,
            originalInviteSdp = sdp,
            fallbackTarget = to,
        )
    }

    private fun registerOutgoingInviteResponseCallback(
        outgoingInviteCallId: String,
        outgoingDialogNextCseq: AtomicInteger,
        myHeaders: Map<String, List<String>>,
        rtpSocket: DatagramSocket,
        amrNbTrack: Int,
        dtmfNbTrack: Int,
        to: String,
        sdp: ByteArray,
    ) {
        val prackedReliableProvisionals = mutableSetOf<String>()
        setResponseCallback(outgoingInviteCallId) { response: SipResponse ->
            handleOutgoingInviteResponse(
                response = response,
                outgoingInviteCallId = outgoingInviteCallId,
                prackedReliableProvisionals = prackedReliableProvisionals,
                outgoingDialogNextCseq = outgoingDialogNextCseq,
                myHeaders = myHeaders,
                rtpSocket = rtpSocket,
                amrNbTrack = amrNbTrack,
                dtmfNbTrack = dtmfNbTrack,
                to = to,
                sdp = sdp,
            )
        }
    }


    private fun prepareInitialOutgoingInviteSendState(
        msg: SipRequest,
        destination: String,
        rtpSocket: DatagramSocket,
        body: ByteArray,
    ): InitialOutgoingInviteSendState {
        val prepared = SipOutgoingInviteInitialSend.prepare(
            msg = msg,
            destination = destination,
            rtpSocket = rtpSocket,
            body = body,
        )
        pendingOutgoingInvite = prepared.pendingInvite
        return prepared.sendState
    }


    private fun writeInitialOutgoingInvite(
        msg: SipRequest,
        phoneNumber: String,
        normalizedPhoneNumber: String,
        destination: String,
        rtpSocket: DatagramSocket,
        body: ByteArray,
    ) {
        SipOutgoingInviteInitialSend.write(
            logTag = TAG,
            msg = msg,
            phoneNumber = phoneNumber,
            normalizedPhoneNumber = normalizedPhoneNumber,
            destination = destination,
            rtpSocket = rtpSocket,
            body = body,
            debugContext = { context -> imsDualSimDebugContext(context) },
            writeBytes = { bytes -> writeSipBytesWithFlush(socket.gWriter(), "SipHandler msg", bytes) },
        )
    }


    private fun registerInitialOutgoingInviteResponseCallback(
        sdpOffer: OutgoingInviteSdpOffer,
        requestContext: OutgoingInviteRequestContext,
        sendState: InitialOutgoingInviteSendState,
        rtpSocket: DatagramSocket,
    ) {
        registerOutgoingInviteResponseCallback(
            outgoingInviteCallId = sendState.callId,
            outgoingDialogNextCseq = sendState.outgoingDialogNextCseq,
            myHeaders = requestContext.baseHeaders,
            rtpSocket = rtpSocket,
            amrNbTrack = sdpOffer.amrNbTrack,
            dtmfNbTrack = sdpOffer.dtmfNbTrack,
            to = requestContext.telUri,
            sdp = sdpOffer.sdp,
        )
    }

    private fun sendInitialOutgoingInvite(
        phoneNumber: String,
        rtpSocket: DatagramSocket,
    ) {
        val outgoingInviteSdpOffer = buildOutgoingInviteSdpOffer(rtpSocket)
        val outgoingInviteBody = outgoingInviteSdpOffer.inviteBody

        val outgoingInviteRequestContext = buildOutgoingInviteRequest(
            phoneNumber = phoneNumber,
            outgoingInviteBody = outgoingInviteBody,
        )
        val msg = outgoingInviteRequestContext.request
        val singtelStockOutgoingTargetUri = outgoingInviteRequestContext.targetUri
        val normalizedPhoneNumber = outgoingInviteRequestContext.normalizedPhoneNumber

        val initialOutgoingInviteSendState = prepareInitialOutgoingInviteSendState(
            msg = msg,
            destination = singtelStockOutgoingTargetUri,
            rtpSocket = rtpSocket,
            body = outgoingInviteBody,
        )
        registerInitialOutgoingInviteResponseCallback(
            sdpOffer = outgoingInviteSdpOffer,
            requestContext = outgoingInviteRequestContext,
            sendState = initialOutgoingInviteSendState,
            rtpSocket = rtpSocket,
        )
        writeInitialOutgoingInvite(
            msg = msg,
            phoneNumber = phoneNumber,
            normalizedPhoneNumber = normalizedPhoneNumber,
            destination = singtelStockOutgoingTargetUri,
            rtpSocket = rtpSocket,
            body = outgoingInviteBody,
        )
    }

    fun call(phoneNumber: String) {
        thread {
            callStopped.set(false)
            callStarted.set(false)
            threadsStarted.set(false)
            callGeneration.incrementAndGet()
            clearPendingOutgoingInvite(closeRtpSocket = true, reason = "new outgoing call")

            val rtpSocket = createOutgoingCallRtpSocket() ?: return@thread

            sendInitialOutgoingInvite(
                phoneNumber = phoneNumber,
                rtpSocket = rtpSocket,
            )
        }
    }


    private fun receiveDownlinkRtpPacket(
        receiveCall: Call,
        dgram: DatagramPacket,
        generation: Int,
    ): Boolean? {
        return try {
            receiveCall.rtpSocket.receive(dgram)
            true
        } catch (e: SocketTimeoutException) {
            // Expected idle receive window. Keep looping, but do not
            // hold the DatagramSocket monitor for seconds or spam logs.
            if (callStopped.get() || callGeneration.get() != generation || receiveCall.rtpSocket.isClosed) {
                null
            } else {
                false
            }
        } catch (e: SocketException) {
            if (callStopped.get() || callGeneration.get() != generation || receiveCall.rtpSocket.isClosed) {
                Rlog.d(
                    TAG,
                    "RTP receive socket closed; exiting decode thread: " +
                        "outgoing=${receiveCall.outgoing} " +
                        "local=${receiveCall.rtpSocket.localAddress}:${receiveCall.rtpSocket.localPort} " +
                        "callStopped=${callStopped.get()} " +
                        "genMismatch=${callGeneration.get() != generation} " +
                        "closed=${receiveCall.rtpSocket.isClosed}",
                )
            } else {
                Rlog.w(
                    TAG,
                    "RTP receive socket exception; exiting decode thread: " +
                        "outgoing=${receiveCall.outgoing} " +
                        "local=${receiveCall.rtpSocket.localAddress}:${receiveCall.rtpSocket.localPort} " +
                        "connected=${receiveCall.rtpSocket.isConnected} " +
                        "remote=${receiveCall.rtpRemoteAddr}:${receiveCall.rtpRemotePort}",
                    e,
                )
            }
            null
        } catch (t: Throwable) {
            Rlog.w(TAG, "Unexpected RTP receive failure; exiting decode thread", t)
            null
        }
    }


    private fun handleReceivedDownlinkRtpPacket(
        receiveCall: Call,
        dgram: DatagramPacket,
        dgramBuf: ByteArray,
        receivedCount: Int,
        audioCodec: NegotiatedAudioCodec,
        decoder: android.media.MediaCodec,
        downlinkPlayoutBuffers: SipDownlinkPcmPlayoutBuffers,
    ) {
        if (receiveCall.outgoing) {
            if (callStarted.get()) {
                receiveCall.outgoingRtpReceived.set(true)
                maybeNotifyOutgoingCallConnected(receiveCall, "first post-answer remote RTP")
            } else if (receivedCount == 1) {
                val earlyCallId = receiveCall.callHeaders["call-id"]?.getOrNull(0).orEmpty()
                Rlog.d(TAG, "Outgoing early-media RTP before final answer; not marking connected callId=$earlyCallId")
            }
        }

        // Check RTP payload type and convert AMR-NB bandwidth-efficient RTP
        // payloads into generic AMR storage frames for MediaCodec.  The old code
        // only decoded FT=7, which made calls silent whenever the network switched
        // to a lower AMR mode such as FT=2.
        val pt = SipRtpPacketParser.payloadType(dgramBuf)
        val amrFrame = SipAmrRtpPayload.storageFrameFromBandwidthEfficientRtp(audioCodec, dgramBuf, dgram.length)
        val ftForLog = amrFrame?.ft ?: 15

        SipRtpPacketLogger.logReceivedPacket(
            logTag = TAG,
            receivedCount = receivedCount,
            packet = dgram,
            payloadType = pt,
            frameType = ftForLog,
            codecFrameSize = amrFrame?.codecFrame?.size ?: 0,
        )

        if (amrFrame == null) return

        SipDownlinkAudioDecoder.queueCodecFrameAndDrainPcm(
            logTag = TAG,
            decoder = decoder,
            codecFrame = amrFrame.codecFrame,
            pcmQueue = downlinkPlayoutBuffers.pcmQueue,
        )
    }


    private data class DownlinkAudioRuntime(
        val audioTrack: android.media.AudioTrack,
        val decoder: android.media.MediaCodec,
        val playoutBuffers: SipDownlinkPcmPlayoutBuffers,
        val playoutThread: Thread,
        val previousAudioMode: Int,
    )

    private fun createDownlinkAudioRuntime(
        audioCodec: NegotiatedAudioCodec,
        generation: Int,
    ): DownlinkAudioRuntime {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            Rlog.d(TAG, "Downlink RTP/decode thread priority set to urgent audio")
        } catch (t: Throwable) {
            Rlog.w(TAG, "Failed to set downlink RTP/decode thread priority", t)
        }

        Rlog.d(TAG, "Decode thread started: codec=${audioCodec.name}/${audioCodec.sampleRate} gen=$generation")
        val audioManager = ctxt.getSystemService(android.media.AudioManager::class.java)
        val prevDecodeAudioMode = audioManager.mode
        if (prevDecodeAudioMode != AudioManager.MODE_IN_COMMUNICATION) {
            Rlog.d(TAG, "Decode thread forcing MODE_IN_COMMUNICATION before AudioTrack: was=$prevDecodeAudioMode")
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        val audioTrack = SipAudioTrackFactory.createVoiceCallTrack(
            audioCodec = audioCodec,
        )
        audioTrack.play()
        // PhhIms downlink PCM playout smoother: decouple RTP receive jitter
        // from AudioTrack writes. IVR/transfer gateways can burst packets or
        // send sparse SID/CN frames after DTMF; writing only when RTP arrives
        // lets AudioTrack underrun and sounds like heavy stutter. Keep a tiny
        // 20ms playout loop and feed silence when the decoder has no PCM ready.
        val downlinkPlayoutBuffers = SipDownlinkPcmPlayoutBuffers.create(audioCodec)
        val downlinkPlayoutThread = SipDownlinkPcmPlayout.start(
            logTag = TAG,
            audioTrack = audioTrack,
            audioCodec = audioCodec,
            buffers = downlinkPlayoutBuffers,
            callStopped = callStopped,
            callGeneration = callGeneration,
            generation = generation,
        )

        val decoder = SipAudioCodecFactory.createStartedDecoder(
            audioCodec = audioCodec,
        )

        return DownlinkAudioRuntime(
            audioTrack = audioTrack,
            decoder = decoder,
            playoutBuffers = downlinkPlayoutBuffers,
            playoutThread = downlinkPlayoutThread,
            previousAudioMode = prevDecodeAudioMode,
        )
    }


    private fun runDownlinkRtpReceiveLoop(
        audioCodec: NegotiatedAudioCodec,
        decoder: android.media.MediaCodec,
        downlinkPlayoutBuffers: SipDownlinkPcmPlayoutBuffers,
        generation: Int,
    ): Int {
        var receivedCount = 0
        while (true) {
            if (callStopped.get() || callGeneration.get() != generation) break
            val dgramBuf = ByteArray(2048)
            val dgram = DatagramPacket(dgramBuf, dgramBuf.size)
            val receiveCall = currentCall ?: break
            when (receiveDownlinkRtpPacket(receiveCall, dgram, generation)) {
                true -> Unit
                false -> continue
                null -> break
            }
            if (callStopped.get() || callGeneration.get() != generation) break
            receivedCount++
            handleReceivedDownlinkRtpPacket(
                receiveCall = receiveCall,
                dgram = dgram,
                dgramBuf = dgramBuf,
                receivedCount = receivedCount,
                audioCodec = audioCodec,
                decoder = decoder,
                downlinkPlayoutBuffers = downlinkPlayoutBuffers,
            )
        }
        return receivedCount
    }

    fun callDecodeThread() {
        val audioCodec = currentCall?.audioCodec ?: SipAudioCodecs.AMR_NB
        val gen = callGeneration.get()
        // Receiving thread
        thread {
            val downlinkRuntime = createDownlinkAudioRuntime(
                audioCodec = audioCodec,
                generation = gen,
            )
            val audioTrack = downlinkRuntime.audioTrack
            val decoder = downlinkRuntime.decoder
            val downlinkPlayoutBuffers = downlinkRuntime.playoutBuffers
            val downlinkPlayoutThread = downlinkRuntime.playoutThread
            val prevDecodeAudioMode = downlinkRuntime.previousAudioMode

            val receivedCount = runDownlinkRtpReceiveLoop(
                audioCodec = audioCodec,
                decoder = decoder,
                downlinkPlayoutBuffers = downlinkPlayoutBuffers,
                generation = gen,
            )
            SipDownlinkAudioCleanup.cleanup(
                logTag = TAG,
                context = ctxt,
                audioTrack = audioTrack,
                decoder = decoder,
                playoutBuffers = downlinkPlayoutBuffers,
                playoutThread = downlinkPlayoutThread,
                callStopped = callStopped,
                callGeneration = callGeneration,
                generation = gen,
                receivedCount = receivedCount,
                previousAudioMode = prevDecodeAudioMode,
            )
        }
    }


    fun sendDtmf(c: Char, durationMs: Int = 160) {
        val call = currentCall
        if (call == null) {
            Rlog.w(TAG, "sendDtmf without current call")
            return
        }
        val event = SipDtmfEventMapper.eventForChar(c)
        if (event == null) {
            Rlog.w(TAG, "Ignoring unsupported DTMF char: $c")
            return
        }

        thread {
            try {
                // RFC 4733 telephone-event. Keep one RTP timestamp for the whole event,
                // increase duration, and repeat the final packet with the E bit set.
                val dtmfCall = currentCall ?: call
                val timestamp = SipDtmfTimestampAllocator.allocate(
                    audioCodec = dtmfCall.audioCodec,
                    durationMs = durationMs,
                    mediaTimestampSamples = rtpTimestampSamples,
                    dtmfTimestampSamples = rtpDtmfTimestampSamples,
                )
                val durationSamples = (durationMs.coerceAtLeast(160) * dtmfCall.audioCodec.sampleRate) / 1000
                val steps = SipDtmfEventMapper.durationSteps(durationSamples)
                Rlog.d(TAG, "Sending RTP DTMF event=$event char=$c payload=${dtmfCall.dtmfTrack} durationMs=$durationMs timestamp=$timestamp sequenceBase=${rtpSequenceNumber.get()} remote=${dtmfCall.rtpRemoteAddr}:${dtmfCall.rtpRemotePort}")
                for ((index, duration) in steps.withIndex()) {
                    val sendCall = currentCall ?: return@thread
                    val sequenceNumber = rtpSequenceNumber.getAndIncrement()
                    val buf = SipDtmfRtpPacketBuilder.buildTelephoneEventPacket(
                        payloadType = sendCall.dtmfTrack,
                        sequenceNumber = sequenceNumber,
                        timestamp = timestamp,
                        event = event,
                        duration = duration,
                        repeatIndex = index,
                    )
                    if (!RtpPacketSender.send(
                        tag = TAG,
                        rtpSocket = sendCall.rtpSocket,
                        bytes = buf,
                        remoteAddr = sendCall.rtpRemoteAddr,
                        remotePort = sendCall.rtpRemotePort,
                        label = "RTP DTMF event=$event char=$c seq=$sequenceNumber ts=$timestamp duration=$duration end=${index >= 3}",
                    )) return@thread
                    Thread.sleep(20)
                }
            } catch (t: Throwable) {
                Rlog.e(TAG, "Failed to send RTP DTMF char=$c", t)
            }
        }
    }

    val callStopped = AtomicBoolean(false)
    val callStarted = AtomicBoolean(false)
    val updateReceived = AtomicBoolean(false)
    val threadsStarted = AtomicBoolean(false)
    val callGeneration = AtomicInteger(0)
    private val rtpSequenceNumber = AtomicInteger(0)
    private val rtpTimestampSamples = AtomicInteger(0)
    // PhhIms DTMF timestamp guard: each telephone-event digit must get a
    // fresh RTP timestamp even if the normal uplink encoder timestamp stalls.
    private val rtpDtmfTimestampSamples = AtomicInteger(0)

    private val prAckWaitTracker = PrackWaitTracker()


    private fun updateCurrentCallFromInDialogInviteSdp(
        call: Call,
        request: SipRequest,
        answerSdp: ByteArray,
        amrTrack: Int,
        amrTrackDesc: String,
        dtmfTrack: Int,
        dtmfTrackDesc: String,
        rtpRemoteAddr: InetAddress,
        rtpRemotePort: Int,
    ): Call {
        val updateState = SipInDialogInvite.callUpdateState(
            request = request,
            answerSdp = answerSdp,
            amrTrack = amrTrack,
            amrTrackDesc = amrTrackDesc,
            dtmfTrack = dtmfTrack,
            dtmfTrackDesc = dtmfTrackDesc,
            rtpRemoteAddr = rtpRemoteAddr,
            rtpRemotePort = rtpRemotePort,
            fallbackRemoteContact = call.remoteContact,
            extractDestinationFromContact = { contact -> extractDestinationFromContact(contact) },
        )
        val updatedCall = call.copy(
            amrTrack = updateState.amrTrack,
            amrTrackDesc = updateState.amrTrackDesc,
            dtmfTrack = updateState.dtmfTrack,
            dtmfTrackDesc = updateState.dtmfTrackDesc,
            sdp = updateState.answerSdp,
            rtpRemoteAddr = updateState.rtpRemoteAddr,
            rtpRemotePort = updateState.rtpRemotePort,
            remoteContact = updateState.remoteContact,
        )
        currentCall = updatedCall
        return updatedCall
    }


    private fun handleInDialogInvite(request: SipRequest, call: Call, responseWriter: OutputStream): Int {
        val callId = request.callIdOrEmpty()
        val cseq = request.headers["cseq"]?.getOrNull(0).orEmpty()
        val offer = SipInDialogInvite.parseSdpOffer(
            request = request,
            callId = callId,
            cseq = cseq,
            logTag = TAG,
        ) ?: return 488
        val sdp = offer.sdp
        val rtpRemoteAddr = offer.rtpRemoteAddr
        val rtpRemotePort = offer.rtpRemotePort
        val attributes = offer.attributes

        val mediaSelection = SipInDialogInvite.selectMedia(
            attributes = attributes,
            selectedAudioCodec = call.audioCodec,
            logTag = TAG,
        ) ?: return 488
        val selectedAudioCodec = mediaSelection.selectedAudioCodec
        val amrTrack = mediaSelection.amrTrack
        val amrTrackDesc = mediaSelection.amrTrackDesc
        val amrFmtpAnswer = mediaSelection.amrFmtpAnswer
        val dtmfTrack = mediaSelection.dtmfTrack
        val dtmfTrackDesc = mediaSelection.dtmfTrackDesc
        val localSdpSessionVersion = call.localSdpVersion.incrementAndGet().coerceAtLeast(3)
        val answerSdp = SipInDialogInvite.buildAnswerSdp(
            attributes = attributes,
            sdp = sdp,
            selectedAudioCodec = selectedAudioCodec,
            amrTrack = amrTrack,
            amrTrackDesc = amrTrackDesc,
            amrFmtpAnswer = amrFmtpAnswer,
            dtmfTrack = dtmfTrack,
            dtmfTrackDesc = dtmfTrackDesc,
            localSdpSessionVersion = localSdpSessionVersion,
            callId = call.callIdOrEmpty(),
            localAddr = socket.gLocalAddr(),
            localRtpPort = call.rtpSocket.localPort,
            logTag = TAG,
        )


        updateCurrentCallFromInDialogInviteSdp(
            call = call,
            request = request,
            answerSdp = answerSdp,
            amrTrack = amrTrack,
            amrTrackDesc = amrTrackDesc,
            dtmfTrack = dtmfTrack,
            dtmfTrackDesc = dtmfTrackDesc,
            rtpRemoteAddr = rtpRemoteAddr,
            rtpRemotePort = rtpRemotePort,
        )
        val inDialogSessionTimerHeaders = SipInDialogInvite.sessionTimerHeaders(
            request = request,
            logTag = TAG,
        )

        val response = SipInDialogInvite.okResponseWithSdp(
            request = request,
            contact = call.callHeaders["contact"]!!.first(),
            answerSdp = answerSdp,
            inDialogSessionTimerHeaders = inDialogSessionTimerHeaders,
            logTag = TAG,
        )


        SipInDialogInvite.writeOkResponse(
            responseWriter = responseWriter,
            response = response,
        )
        return 0
    }


    private fun rejectRecentlyTerminatedIncomingInviteIfNeeded(
        incomingCallId: String,
        request: SipRequest,
    ): Int? {
        val maybeCurrentCall = currentCall
        return SipIncomingInviteDialogSetup.rejectRecentlyTerminatedInviteIfNeeded(
            logTag = TAG,
            incomingCallId = incomingCallId,
            request = request,
            wasRecentlyTerminated = wasRecentlyTerminatedIncomingCall(incomingCallId),
            currentCallId = maybeCurrentCall?.callIdOrEmpty(),
            currentCallOutgoing = maybeCurrentCall?.outgoing,
            incomingAcceptedAwaitingAck = incomingAcceptedAwaitingAck.get(),
            incomingFinalResponseSent = incomingFinalResponseSent.get(),
            incomingHangupAfterAck = incomingHangupAfterAck.get(),
        )
    }


    private fun handleDuplicateIncomingInviteForExistingDialog(
        request: SipRequest,
        incomingCallId: String,
        incomingResponseWriter: OutputStream,
        existingCall: Call?,
    ): Int? {
        if (existingCall == null || existingCall.outgoing || existingCall.callIdOrEmpty() != incomingCallId) {
            return null
        }

        val incomingCseq = request.headers["cseq"]?.getOrNull(0).orEmpty()
        val duplicateAnswered = incomingFinalResponseSent.get() || incomingAcceptedAwaitingAck.get() || callStarted.get()
        val refreshedHeaders = SipDialogHeaderBuilder.responseHeadersFromRequest(
            request = request,
            toOverride = existingCall.callHeaders["to"],
        )
        val refreshedCall = existingCall.copy(
            callHeaders = existingCall.callHeaders + refreshedHeaders,
            incomingResponseWriter = incomingResponseWriter,
        )
        currentCall = refreshedCall

        Rlog.w(
            TAG,
            SipIncomingInviteRequestFlowLogs.duplicateIncomingInviteRefreshLog(
                callId = incomingCallId,
                cseq = incomingCseq,
                finalResponseSent = incomingFinalResponseSent.get(),
                awaitingAck = incomingAcceptedAwaitingAck.get(),
                callStarted = callStarted.get(),
            ),
        )

        if (duplicateAnswered) {
            val duplicateOmitFinalSdp = refreshedCall.hasEarlyMedia
            val duplicateFinalBody = if (!duplicateOmitFinalSdp) {
                completeIncomingPreconditionAnswerSdp(refreshedCall.sdp, incomingCallId)
            } else {
                ByteArray(0)
            }
            val duplicateFinalCall = if (!duplicateOmitFinalSdp && !duplicateFinalBody.contentEquals(refreshedCall.sdp)) {
                refreshedCall.copy(sdp = duplicateFinalBody)
            } else {
                refreshedCall
            }
            currentCall = duplicateFinalCall

            val duplicateFinalResponse = SipIncomingInviteFinalResponses.duplicateFinalResponse(
                callHeaders = duplicateFinalCall.callHeaders,
                contact = duplicateFinalCall.callHeaders["contact"]!!.first(),
                body = duplicateFinalBody,
                omitFinalSdp = duplicateOmitFinalSdp,
            )
            val duplicateFinalBytes = duplicateFinalResponse.toByteArray()
            Rlog.w(
                TAG,
                SipIncomingInviteFinalResponses.duplicateFinalResponseResendLog(
                    incomingCallId = incomingCallId,
                    incomingCseq = incomingCseq,
                    responseBytesSize = duplicateFinalBytes.size,
                ),
            )
            if (writeSipBytes(
                    incomingResponseWriter,
                    duplicateFinalBytes,
                    SipIncomingInviteFinalResponses.duplicateFinalResponseWriteContext(
                        incomingCallId = incomingCallId,
                        incomingCseq = incomingCseq,
                    ),
                )
            ) {
                incomingFinalResponseSent.set(true)
                incomingAcceptedAwaitingAck.set(true)
            } else {
                Rlog.w(
                    TAG,
                    SipIncomingInviteFinalResponses.duplicateFinalResponseFailureLog(
                        incomingCallId = incomingCallId,
                        incomingCseq = incomingCseq,
                    ),
                )
            }
            return 0
        }

        return 100
    }


    private fun rejectIncomingInviteWhileBusyOrOutgoingPending(
        request: SipRequest,
        incomingCallId: String,
        existingCall: Call?,
    ): Int? {
        val decision = SipIncomingInviteDialogSetup.rejectWhileBusyOrOutgoingPending(
            logTag = TAG,
            request = request,
            incomingCallId = incomingCallId,
            activeCallId = existingCall?.callHeaders?.get("call-id")?.getOrNull(0),
            activeCallOutgoing = existingCall?.outgoing,
            pendingOutgoingCallId = pendingOutgoingInvite?.callId,
        ) ?: return null

        rememberTerminatedIncomingCall(incomingCallId, decision.terminatedReason)
        return decision.statusCode
    }


    private fun resetIncomingCallStateForNewInvite() {
        callStopped.set(false)
        callStarted.set(false)
        threadsStarted.set(false)
        callGeneration.incrementAndGet()
        incomingFinalResponseSent.set(false)
        incomingAcceptedAwaitingAck.set(false)
        incomingHangupAfterAck.set(false)
        currentCall = null
        prAckWaitTracker.clearAndNotifyAll()
    }


    private fun sendExplicitTryingForIncomingInvite(
        request: SipRequest,
        incomingResponseWriter: OutputStream,
    ) {
        val trying = SipIncomingInviteDialogSetup.explicitTryingResponse(request)
        Rlog.d(TAG, SipIncomingInviteRequestFlowLogs.explicitTryingLog(trying))
        synchronized(incomingResponseWriter) { incomingResponseWriter.write(trying.toByteArray()) }
    }


    private fun parseIncomingInviteOffer(
        request: SipRequest,
        incomingCallId: String,
    ): IncomingInviteOffer? =
        SipIncomingInviteOfferParser.parse(
            request = request,
            incomingCallId = incomingCallId,
            logTag = TAG,
            hasIncomingResponseWriter = requestWriters.containsKey(incomingCallId),
            amrWbMediaCodecAvailable = amrWbMediaCodecAvailable,
            extractCallerNumberFromHeader = { header -> extractCallerNumberFromHeader(header) },
        )


    private fun createIncomingInviteRtpSocket(
        rtpRemoteAddr: InetAddress,
        rtpRemotePort: String,
    ): DatagramSocket? =
        SipIncomingInviteDialogSetup.createIncomingRtpSocket(
            logTag = TAG,
            localAddr = localAddr,
            rtpRemoteAddr = rtpRemoteAddr,
            rtpRemotePort = rtpRemotePort,
            bindSocket = { rtpSocket -> network.bindSocket(rtpSocket) },
            reconnectIms = { reason -> reconnectIms(reason) },
        )


    private fun incomingInviteDialogContact(
        request: SipRequest,
        owner: String,
        incomingCallId: String,
    ): String {
        val local = SipIncomingInviteDialogSetup.localDialogEndpoint(
            localHost = socket.gLocalAddr().hostAddress,
            isIpv6 = socket.gLocalAddr() is Inet6Address,
            port = serverSocket.localPort,
        )
        return SipIncomingInviteDialogSetup.buildDialogContact(
            logTag = TAG,
            request = request,
            owner = owner,
            incomingCallId = incomingCallId,
            localEndpoint = local,
            fallbackTransport = SipContactHeaders.transport(socket),
            imei = imei,
        )
    }


    private fun abortIncomingCallSetupIfTerminated(
        incomingCallId: String,
        rtpSocket: DatagramSocket,
    ): Boolean =
        SipIncomingInviteDialogSetup.abortSetupIfTerminated(
            logTag = TAG,
            incomingCallId = incomingCallId,
            rtpSocket = rtpSocket,
            wasTerminated = wasRecentlyTerminatedIncomingCall(incomingCallId),
        )


    private fun installIncomingCallDialogAndNotify(
        request: SipRequest,
        incomingCallId: String,
        callerNumber: String,
        selectedAudioCodec: NegotiatedAudioCodec,
        amrTrack: Int,
        amrTrackDesc: String,
        dtmfTrack: Int,
        dtmfTrackDesc: String,
        callHeaders: Map<String, List<String>>,
        rtpRemoteAddr: InetAddress,
        rtpRemotePort: String,
        rtpSocket: DatagramSocket,
        sdp: ByteArray,
        sendReliable183: Boolean,
        incomingResponseWriter: OutputStream,
    ): Boolean {
        currentCall = Call(
            outgoing = false,
            audioCodec = selectedAudioCodec,
            amrTrack = amrTrack,
            amrTrackDesc = amrTrackDesc,
            dtmfTrack = dtmfTrack,
            dtmfTrackDesc = dtmfTrackDesc,
            callHeaders = callHeaders - "require" - "content-type" - "p-access-network-info" + "Supported: replaces, timer".toSipHeadersMap(),
            rtpRemoteAddr = rtpRemoteAddr,
            rtpRemotePort = rtpRemotePort.toInt(),
            rtpSocket =  rtpSocket,
            sdp = sdp,
            hasEarlyMedia = sendReliable183,
            remoteContact = extractDestinationFromContact(request.headers["contact"]!![0]),
            incomingResponseWriter = incomingResponseWriter,
        )
        val installedIncomingCall = currentCall
        val installedIncomingCallId = installedIncomingCall?.callIdOrEmpty().orEmpty()
        val installAbortDecision = SipIncomingInviteDialogSetup.installAbortDecision(
            incomingCallId = incomingCallId,
            wasRecentlyTerminated = wasRecentlyTerminatedIncomingCall(incomingCallId),
            installedIncomingCallId = installedIncomingCallId,
            installedStillCurrent = installedIncomingCall === currentCall,
        )
        if (installAbortDecision != null) {
            Rlog.w(TAG, installAbortDecision.message)
            if (installAbortDecision.clearCurrentCall) {
                currentCall = null
            }
            try { rtpSocket.close() } catch (t: Throwable) { Rlog.d(TAG, SipIncomingInviteRequestFlowLogs.abortedIncomingRtpSocketCloseFailedLog(), t) }
            return false
        }
        onIncomingCall?.invoke(
            Object(),
            callerNumber,
            SipIncomingInviteDialogSetup.incomingCallNotificationExtras(
                incomingCallId = incomingCallId,
                selectedAudioCodec = selectedAudioCodec,
            ),
        )

        Rlog.d(TAG, SipIncomingInviteRequestFlowLogs.deferringIncomingMediaUntilFinalAckLog())
        return true
    }


    private fun sendIncomingInviteProvisionalResponse(
        incomingResponseWriter: OutputStream,
        sendReliable183: Boolean,
        reliableSequence: Int,
        headers: Map<String, List<String>>,
        sdp: ByteArray,
    ) {
        if (sendReliable183) {
            prAckWaitTracker.add(reliableSequence)
            val msg = SipIncomingInviteDialogSetup.reliableProvisionalResponse(
                headers = headers,
                sdp = sdp,
            )
            Rlog.d(TAG, SipIncomingInviteRequestFlowLogs.reliableIncoming183Log(msg))
            writeSipBytesWithFlush(incomingResponseWriter, "SipHandler msg", msg.toByteArray())
            waitPrack(reliableSequence)
        } else {
            val msg2 = SipIncomingInviteDialogSetup.plainRingingResponse(headers)
            Rlog.d(TAG, SipIncomingInviteRequestFlowLogs.plainIncoming180Log(msg2))
            synchronized(incomingResponseWriter) { incomingResponseWriter.write(msg2.toByteArray()) }
        }
    }


    private fun prepareIncomingInviteDialogSetupState(
        request: SipRequest,
        incomingCallId: String,
        incomingOffer: IncomingInviteOffer,
    ): IncomingInviteDialogSetupState? {
        val rtpRemoteAddr = incomingOffer.rtpRemoteAddr
        val rtpRemotePort = incomingOffer.rtpRemotePort
        val owner = incomingOffer.owner

        // Need to sleep a bit so that our 100 Trying is sent first. Kinda weird.
        Thread.sleep(500)
        val rtpSocket = createIncomingInviteRtpSocket(
            rtpRemoteAddr = rtpRemoteAddr,
            rtpRemotePort = rtpRemotePort,
        ) ?: return null

        val dialogContact = incomingInviteDialogContact(
            request = request,
            owner = owner,
            incomingCallId = incomingCallId,
        )
        return SipIncomingInviteDialogSetup.buildDialogSetupState(
            request = request,
            incomingCallId = incomingCallId,
            incomingOffer = incomingOffer,
            rtpSocket = rtpSocket,
            dialogContact = dialogContact,
            commonHeaders = commonHeaders,
            reliableSequence = reliableSequenceCounter++,
            localToTag = randomBytes(6).toHex(),
            localAddr = socket.gLocalAddr(),
            logTag = TAG,
        )
    }


    private fun installIncomingDialogAndSendProvisionalResponse(
        request: SipRequest,
        incomingCallId: String,
        incomingResponseWriter: OutputStream,
        incomingOffer: IncomingInviteOffer,
        setupState: IncomingInviteDialogSetupState,
    ) {
        if (!installIncomingCallDialogAndNotify(
                request = request,
                incomingCallId = incomingCallId,
                callerNumber = incomingOffer.callerNumber,
                selectedAudioCodec = incomingOffer.selectedAudioCodec,
                amrTrack = incomingOffer.amrTrack,
                amrTrackDesc = incomingOffer.amrTrackDesc,
                dtmfTrack = incomingOffer.dtmfTrack,
                dtmfTrackDesc = incomingOffer.dtmfTrackDesc,
                callHeaders = setupState.headers,
                rtpRemoteAddr = incomingOffer.rtpRemoteAddr,
                rtpRemotePort = incomingOffer.rtpRemotePort,
                rtpSocket = setupState.rtpSocket,
                sdp = setupState.sdp,
                sendReliable183 = incomingOffer.sendReliable183,
                incomingResponseWriter = incomingResponseWriter,
            )
        ) return

        sendIncomingInviteProvisionalResponse(
            incomingResponseWriter = incomingResponseWriter,
            sendReliable183 = incomingOffer.sendReliable183,
            reliableSequence = setupState.reliableSequence,
            headers = setupState.headers,
            sdp = setupState.sdp,
        )
    }

    private fun runIncomingInviteDialogSetup(
        request: SipRequest,
        incomingCallId: String,
        incomingResponseWriter: OutputStream,
        incomingOffer: IncomingInviteOffer,
    ) {
        val incomingInviteDialogSetupState = prepareIncomingInviteDialogSetupState(
            request = request,
            incomingCallId = incomingCallId,
            incomingOffer = incomingOffer,
        ) ?: return

        if (abortIncomingCallSetupIfTerminated(
                incomingCallId = incomingCallId,
                rtpSocket = incomingInviteDialogSetupState.rtpSocket,
            )
        ) return

        installIncomingDialogAndSendProvisionalResponse(
            request = request,
            incomingCallId = incomingCallId,
            incomingResponseWriter = incomingResponseWriter,
            incomingOffer = incomingOffer,
            setupState = incomingInviteDialogSetupState,
        )
    }

    private fun startIncomingInviteDialogSetup(
        request: SipRequest,
        incomingCallId: String,
        incomingResponseWriter: OutputStream,
        incomingOffer: IncomingInviteOffer,
    ) {
        thread {
            runIncomingInviteDialogSetup(
                request = request,
                incomingCallId = incomingCallId,
                incomingResponseWriter = incomingResponseWriter,
                incomingOffer = incomingOffer,
            )
        }
    }


    fun handleCall(request: SipRequest): Int {
        val contentType = request.headers["content-type"]?.get(0)
        if (contentType != "application/sdp") return 404
        val incomingCallId = request.headers["call-id"]!![0]
        rejectRecentlyTerminatedIncomingInviteIfNeeded(
            incomingCallId = incomingCallId,
            request = request,
        )?.let { return it }
        val incomingResponseWriter = dispatcher.writerForCallId(incomingCallId) ?: socket.gWriter()
        val existingCall = currentCall
        val isInDialogInvite = existingCall != null &&
            existingCall.callHeaders["call-id"]?.getOrNull(0) == incomingCallId &&
            request.headers["from"]?.any { it.contains(";tag=", ignoreCase = true) } == true &&
            request.headers["to"]?.any { it.contains(";tag=", ignoreCase = true) } == true
        if (isInDialogInvite) {
            return handleInDialogInvite(request, existingCall!!, incomingResponseWriter)
        }
        handleDuplicateIncomingInviteForExistingDialog(
            request = request,
            incomingCallId = incomingCallId,
            incomingResponseWriter = incomingResponseWriter,
            existingCall = existingCall,
        )?.let { return it }

        rejectIncomingInviteWhileBusyOrOutgoingPending(
            request = request,
            incomingCallId = incomingCallId,
            existingCall = existingCall,
        )?.let { return it }

        resetIncomingCallStateForNewInvite()

        val incomingOffer = parseIncomingInviteOffer(
            request = request,
            incomingCallId = incomingCallId,
        ) ?: return 488
        sendExplicitTryingForIncomingInvite(
            request = request,
            incomingResponseWriter = incomingResponseWriter,
        )

        startIncomingInviteDialogSetup(
            request = request,
            incomingCallId = incomingCallId,
            incomingResponseWriter = incomingResponseWriter,
            incomingOffer = incomingOffer,
        )

        // Do not let parseMessage auto-generate a 100 Trying with a different To-tag.
        // The first response for this test path is our explicit 180 Ringing from the call thread.
        return 0
    }

    fun handleSms(request: SipRequest): Int = smsHandler.handleSms(request)

    fun sendSms(
        smsSmsc: String?,
        pdu: ByteArray,
        ref: Int,
        successCb: (() -> Unit),
        failCb: (() -> Unit),
    ) {
        if (smsFallbackPolicy.shouldBypass(realm)) {
            Rlog.w(TAG, "IMS SMS learned fallback: returning framework fallback without SIP MESSAGE")
            failCb()
            return
        }
        smsHandler.sendSms(smsSmsc, pdu, ref, successCb, failCb)
    }

    fun sendSmsAck(token: Int, ref: Int, error: Boolean) {
        smsHandler.sendSmsAck(token, ref, error)
    }
}
