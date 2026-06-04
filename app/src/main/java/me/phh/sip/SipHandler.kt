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
    ).also { it.start() }
    private val carrierSettings = SipCarrierSettings.fromSimOperator(subTelephonyManager.simOperator)
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

    private fun isWaitingForIwlanAfterWfcPreferenceChange(): Boolean {
        if (!wfcSubscriptionSettingMonitor.isWifiPreferredOrOnly()) {
            return false
        }
        if (imsRegistrationTech != REGISTRATION_TECH_LTE) {
            return false
        }

        val elapsedMs = SystemClock.uptimeMillis() - wfcSubscriptionSettingMonitor.lastChangeUptimeMs()
        return elapsedMs in 0L..IWLAN_CONVERGENCE_OUTGOING_CALL_GUARD_MS
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

        if (isWaitingForIwlanAfterWfcPreferenceChange()) {
            val elapsedMs = android.os.SystemClock.uptimeMillis() - wfcSubscriptionSettingMonitor.lastChangeUptimeMs()
            Rlog.w(
                TAG,
                "Rejecting outgoing call while waiting for IWLAN IMS after WFC preference/subscription change: " +
                    "tech=${registrationTechName(imsRegistrationTech)} elapsedMs=$elapsedMs",
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

    fun onWfcDisabled(reason: String) {
        myHandler.post {
            if (pendingCellularReconnectAfterWfcDisable) {
                Rlog.w(TAG, "Ignoring duplicate WFC disabled notification while waiting for cellular IMS link: $reason")
                return@post
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
    fun connect() {
        abandonnedBecauseOfNoPcscf = false
        resetRegistrationStateForConnect()
        Rlog.d(TAG, "Trying to connect to SIP server ${imsDualSimDebugContext()}")
        val lp = connectivityManager.getLinkProperties(network)
        Rlog.d(TAG, "Got link properties $lp")
        if (lp == null) {
            Rlog.w(TAG, "No link properties for IMS network")
            imsFailureCallback?.invoke()
            scheduleImsNetworkRequestRestart("No link properties for current IMS network")
            return
        }
        imsRegistrationTech = detectRegistrationTech(lp)
        Rlog.d(TAG, "IMS registration tech ${registrationTechName(imsRegistrationTech)} interface=${lp.interfaceName} caps=${connectivityManager.getNetworkCapabilities(network)}")
        imsRegisteringCallback?.invoke(imsRegistrationTech)
        when (val endpoint = ImsNetworkState.resolveEndpoint(TAG, lp, mnc, mcc)) {
            is ImsNetworkEndpointResolution.Success -> {
                localAddr = endpoint.localAddr
                pcscfAddr = endpoint.pcscfAddr
            }

            ImsNetworkEndpointResolution.WaitingForPcscf -> {
                abandonnedBecauseOfNoPcscf = true
                return
            }

            ImsNetworkEndpointResolution.NoLocalAddress -> {
                failConnectAndRetry("No usable local address on IMS link properties")
                return
            }
        }

        Rlog.w(TAG, "Connecting with address ${imsDualSimDebugContext("selectedLocal=$localAddr selectedPcscf=$pcscfAddr")}")

        val clientSpiC = allocateSecurityParameterIndexWithWatchdog("client SPI-C", localAddr)
        val clientSpiS = allocateSecurityParameterIndexWithWatchdog("client SPI-S", localAddr, clientSpiC.spi + 1)
        ipsecSettings = SipIpsecSettings(
            clientSpiS = clientSpiS,
            clientSpiC = clientSpiC)
        ipsecResourcesClosed = false

        plainSocket = if (isControlSocketUdp)
            SipConnectionUdp(network, pcscfAddr, localAddr)
        else
            SipConnectionTcp(network, pcscfAddr, localAddr)
        connectSipSocketWithWatchdog(plainSocket, 5060, "plain initial")
        socket = if(plainSocket is SipConnectionTcp)
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
        fun readPlainRegisterReply(): SipMessage? {
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

        var plainRegReply = readPlainRegisterReply()
        Rlog.d(TAG, "Received $plainRegReply")

        if (plainRegReply !is SipResponse || plainRegReply.statusCode != 401) {
            Rlog.w(TAG, "Didn't get expected response from initial register, aborting")
            plainSocket.close()
            failConnectAndRetry("Initial SIP REGISTER did not return 401")
            return
        }

        var registerChallenge = SipRegisterChallengeParser.parse(
            response = plainRegReply,
            fallbackRealm = realm,
        )
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

                val resyncReply = readPlainRegisterReply()
                Rlog.d(TAG, "Received after AKA AUTS resynchronization $resyncReply")
                if (resyncReply !is SipResponse || resyncReply.statusCode != 401) {
                    Rlog.w(TAG, "Didn't get expected 401 after AKA AUTS resynchronization, aborting")
                    plainSocket.close()
                    failConnectAndRetry("AKA AUTS resynchronization REGISTER did not return fresh 401")
                    return
                }

                plainRegReply = resyncReply
                registerChallenge = SipRegisterChallengeParser.parse(
                    response = plainRegReply,
                    fallbackRealm = realm,
                )
                Rlog.d(TAG, "Requesting AKA challenge after AUTS resynchronization")
                when (val retryResult = sipAkaChallengeForRegistration(subTelephonyManager, registerChallenge.nonceB64)) {
                    is SipAkaChallengeResult.Success -> retryResult.akaResult
                    is SipAkaChallengeResult.SynchronizationFailure -> {
                        Rlog.w(TAG, "AKA still returns AUTS after one resynchronization REGISTER; aborting")
                        plainSocket.close()
                        failConnectAndRetry("AKA still out of sync after AUTS resynchronization")
                        return
                    }
                }
            }
        }

        plainSocket.close()

        val registerRealmDecision = SipRegisterNegotiationPolicy.registerRealmDecision(
            defaultRealm = realm,
            challengeRealm = registerChallenge.realm,
            preferCanonicalAfterPromoted494 = preferCanonicalRegisterRealmAfter494,
        )
        val registerDigestUriRealm = registerRealmDecision.targetRealm
        if (registerRealmDecision.forcedCanonical && registerRealmDecision.hasPromotedCandidate) {
            Rlog.w(
                TAG,
                "Keeping challenged REGISTER realm auth-only after previous promoted 494 success: " +
                    "oldUri=sip:$realm promotedUri=sip:${registerRealmDecision.candidateRealm} " +
                    "challengeRealm=${registerChallenge.realm}",
            )
        } else if (registerRealmDecision.usesPromotedChallengeRealm) {
            Rlog.w(
                TAG,
                "Using challenged REGISTER realm as request/digest URI: " +
                    "oldUri=sip:$realm newUri=sip:$registerDigestUriRealm " +
                    "challengeRealm=${registerChallenge.realm}",
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
        akaDigest = SipRegistrationDigestFactory.create(
            user = user,
            realm = registerChallenge.realm,
            uri = "sip:$registerDigestUriRealm",
            nonceB64 = registerChallenge.nonceB64,
            opaque = registerChallenge.opaque,
            akaResult = akaResult,
            useNonsessAka = requireNonsessAka || registerChallenge.qop == null,
        )

        var portS = 5060
        // Check if there is a security-server header in the reply
        if(plainRegReply.headers.containsKey("security-server")) {
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
        connectSipSocketWithWatchdog(socket, portS, "IPsec authenticated")
        updateCommonHeaders(socket)
        register()

        Rlog.d(TAG, "Waiting for authenticated SIP REGISTER response")
        val authenticatedRegisterReader =
            if (socket is SipConnectionTcp) socket.gReader()
            else if (socket is SipConnectionUdp) serverSocketUdp.gReader()
            else socket.gReader()

        val regReply = try {
            authenticatedRegisterReader.parseMessage()
        } catch (t: Throwable) {
            Rlog.w(
                TAG,
                "Authenticated SIP REGISTER response read failed, aborting SIP",
                t,
            )
            failConnectAndRetry("Authenticated SIP REGISTER response read failed")
            return
        }

        if (regReply == null) {
            Rlog.w(
                TAG,
                "Authenticated SIP REGISTER got EOF/no response, aborting SIP",
            )
            failConnectAndRetry("Authenticated SIP REGISTER got EOF/no response")
            return
        }
        Rlog.d(TAG, "Received $regReply")

        if (regReply !is SipResponse || regReply.statusCode != 200) {
            if (
                SipRegisterNegotiationPolicy.shouldRetryCanonicalAfterPromoted494(
                    statusCode = (regReply as? SipResponse)?.statusCode,
                    decision = registerRealmDecision,
                    alreadyPreferCanonical = preferCanonicalRegisterRealmAfter494,
                )
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
                val canonicalRegReply = try {
                    authenticatedRegisterReader.parseMessage()
                } catch (t: Throwable) {
                    Rlog.w(
                        TAG,
                        "Canonical REGISTER realm retry response read failed, aborting SIP",
                        t,
                    )
                    failConnectAndRetry("Canonical REGISTER realm retry response read failed")
                    return
                }

                if (canonicalRegReply == null) {
                    Rlog.w(
                        TAG,
                        "Canonical REGISTER realm retry got EOF/no response, aborting SIP",
                    )
                    failConnectAndRetry("Canonical REGISTER realm retry got EOF/no response")
                    return
                }

                Rlog.d(TAG, "Received after canonical REGISTER realm retry $canonicalRegReply")
                if (canonicalRegReply is SipResponse && canonicalRegReply.statusCode == 200) {
                    preferCanonicalRegisterRealmAfter494 = true
                    Rlog.w(
                        TAG,
                        "Canonical REGISTER realm retry accepted; keeping challenged realms auth-only " +
                            "for this IMS session",
                    )

                    reconnectController.markConnected()

                    installSipCallbacks()
                    handleResponse(canonicalRegReply)

                    startSipReaderLoops()
                    return
                }

                Rlog.w(TAG, "Canonical REGISTER realm retry did not return 200, aborting SIP")
                failConnectAndRetry("Canonical REGISTER realm retry did not return 200")
                return
            }

            Rlog.w(TAG, "Could not connect, aborting SIP")
            failConnectAndRetry("Authenticated SIP REGISTER did not return 200")
            return
        }

        reconnectController.markConnected()

        installSipCallbacks()
        handleResponse(regReply)

        startSipReaderLoops()
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

    fun getVolteNetwork() {
        // TODO add something similar for VoWifi ipsec tunnel?
        Rlog.d(TAG, "Requesting IMS network ${imsDualSimDebugContext()}")
        if (!isRatReadyForImsNetworkRequest()) {
            Rlog.w(TAG, "Deferring IMS network request until LTE/NR/IWLAN is back")
            scheduleImsNetworkRequestRestart("RAT not ready for IMS network request")
            return
        }
        val imsNetworkRequest = ImsNetworkRequestBuilder.buildForSubscription(subId)

        Rlog.d(TAG, "Built subscription-specific IMS network request ${imsDualSimDebugContext("request=$imsNetworkRequest")}")

        unregisterImsNetworkCallback("new IMS network request")

        val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onUnavailable() {
                    Rlog.d(TAG, "IMS network unavailable ${imsDualSimDebugContext()}")
                }

                override fun onLost(lostNetwork: Network) {
                    Rlog.d(TAG, "IMS network lost ${imsDualSimDebugContext("lost=$lostNetwork")}")
                    if (this@SipHandler::network.isInitialized && network == lostNetwork) {
                        try {
                    connectivityManager.unregisterNetworkCallback(this)
                        if (imsNetworkCallback === this) {
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
                scheduleImsNetworkRequestRestart("IMS network lost $lostNetwork")
                    }
                }

                override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                    Rlog.d(TAG, "IMS network blocked status changed ${imsDualSimDebugContext("blocked=$blocked")}")
                }
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    Rlog.d(TAG, "IMS network capabilities changed ${imsDualSimDebugContext("capabilities=$networkCapabilities")}")
                    val isCurrentImsNetwork =
                        this@SipHandler::network.isInitialized &&
                            network == this@SipHandler.network

                    if (isCurrentImsNetwork) {
                        imsTransportGuard.onCapabilitiesChanged(network, networkCapabilities)
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

                override fun onLosing(network: Network, maxMsToLive: Int) {
                    Rlog.d(TAG, "IMS network losing")
                }

                override fun onLinkPropertiesChanged(
                    _network: Network,
                    linkProperties: LinkProperties
                ) {
                    Rlog.d(TAG, "IMS network link properties changed ${imsDualSimDebugContext("linkProperties=$linkProperties")}")
                    val pcscfs = getPcscfServers(linkProperties)
                    val newLocalAddr = getImsLocalAddress(linkProperties)
                    val newPcscfAddr = pcscfs.firstOrNull()
                    Rlog.d(TAG, "Got pcscfs $pcscfs local=$newLocalAddr")
if (pcscfs.isNotEmpty() && abandonnedBecauseOfNoPcscf) {
                        // Switch to this network if it has P-CSCF (could be a different bearer).
                        reconnectIms("P-CSCF appeared after previous no-P-CSCF state", _network)
                        return
                    }

                    if (!this@SipHandler::network.isInitialized) return

                    val oldLocalAddr = if (this@SipHandler::localAddr.isInitialized) localAddr else null
                    val oldPcscfAddr = if (this@SipHandler::pcscfAddr.isInitialized) pcscfAddr else null
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
                            _network,
                            delayMs = 1_000L,
                        )
                        return
                    }

                    val networkChanged = network != _network
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
                            _network,
                            delayMs = if (
                                techChanged &&
                                    oldRegistrationTech == REGISTRATION_TECH_IWLAN &&
                                    newRegistrationTech == REGISTRATION_TECH_LTE
                            ) 6_000L else 1_000L,
                        )
                    }
                }

                override fun onAvailable(_network: Network) {
                    Rlog.d(TAG, "Got IMS network ${imsDualSimDebugContext("network=$_network")}")
                    if (!this@SipHandler::network.isInitialized) {
                        network = _network
                        thread {
                            Thread.sleep(4000)
                            try {
                                connect()
                            } catch (e: Throwable) {
                                Rlog.e(TAG, "connect() failed from IMS network callback", e)
                        failConnectAndRetry("connect() failed from IMS network callback")
                            }
                        }
                    } else if (abandonnedBecauseOfNoPcscf || network != _network) {
                        reconnectIms("new IMS network available old=${network} new=$_network abandoned=$abandonnedBecauseOfNoPcscf", _network, delayMs = 4000L)
                    } else {
                        Rlog.d(TAG, "... already using this IMS network")
                    }
                }
            }

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

    private fun responseHeadersFromRequest(
        request: SipRequest,
        toOverride: List<String>? = null,
        extra: SipHeadersMap = emptyMap(),
    ): SipHeadersMap = SipDialogHeaderBuilder.responseHeadersFromRequest(
        request = request,
        toOverride = toOverride,
        extra = extra,
    )

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
        Rlog.d(TAG, "Received ACK for call-id=$callId current=$currentCallId outgoing=${call?.outgoing}")
        if (call != null && !call.outgoing && currentCallId == callId) {
            callStarted.set(true)
            incomingAcceptedAwaitingAck.set(false)

            if (threadsStarted.compareAndSet(false, true)) {
                Rlog.d(TAG, "Starting incoming media threads from final ACK")
                callDecodeThread()
                callEncodeThread(callSnapshot = call)
            } else {
                Rlog.d(TAG, "Incoming media threads already started before final ACK")
            }

            onIncomingCallConnected?.invoke(
                Object(),
                mapOf("call-id" to callId) + SipAudioCodecNegotiator.audioCodecExtras(call.audioCodec),
            )

            if (incomingHangupAfterAck.getAndSet(false)) {
                Rlog.d(TAG, "ACK received after local pre-ACK hangup; sending deferred BYE")
                sendByeForCall(call)
                rememberTerminatedIncomingCall(callId, "deferred local BYE after ACK")
            currentCall = null
            }
        }
        return 0
    }

    fun handlePrack(request: SipRequest): Int {
        Rlog.d(TAG, "Received PRACK for ${request.headers["rack"]!![0]}")
        val id = request.headers["rack"]!![0].split(" ")[0].toInt()
        prAckWaitTracker.ack(id)
        return 200
    }

    fun handleUpdate(request: SipRequest): Int {
        val requestCallId = request.callIdOrEmpty()
        val requestCseq = request.headers["cseq"]?.getOrNull(0).orEmpty()
        val call = currentCall
        val currentCallId = call?.callIdOrNull()

        if (call == null || currentCallId != requestCallId) {
            Rlog.w(TAG, "Rejecting UPDATE for non-current dialog: callId=$requestCallId cseq=$requestCseq current=$currentCallId")
            return 481
        }

        val updateCallId = request.headers.callIdOrNull()
        val updateResponseWriter = updateCallId?.let { dispatcher.writerForCallId(it) } ?: socket.gWriter()

        fun writeUpdateReply(reply: SipResponse) {
            Rlog.d(TAG, "Replying to UPDATE with $reply")
            synchronized(updateResponseWriter) {
                updateResponseWriter.write(reply.toByteArray())
            }
        }

        val isSdp = request.headers["content-type"]
            ?.getOrNull(0)
            ?.startsWith("application/sdp", ignoreCase = true) == true &&
            request.body.isNotEmpty()

        if (!isSdp) {
            val reply = SipResponse(
                statusCode = 200,
                statusString = "OK",
                headersParam = request.headers.filter { (k, _) ->
                    k in listOf("cseq", "via", "from", "to", "call-id")
                } + """
                    Supported: 100rel, replaces, timer
                    Call-ID: $requestCallId
                    Content-Length: 0
                """.toSipHeadersMap(),
                autofill = false,
            )
            writeUpdateReply(reply)
            return 0
        }

        val sdp = request.body
            .toString(Charsets.UTF_8)
            .split("[\\r\\n]+".toRegex())
            .filter { it.isNotBlank() }

        Rlog.d(TAG, "Handling UPDATE SDP offer: callId=$requestCallId cseq=$requestCseq sdp=$sdp")

        fun sdpElement(command: String): String? {
            val v = sdp.firstOrNull { it.startsWith("$command=") } ?: return null
            return v.substring(2)
        }

        val sdpConnectionData = sdpElement("c")
        val sdpMedia = sdpElement("m")
        if (sdpConnectionData == null || sdpMedia == null) {
            Rlog.w(TAG, "Rejecting UPDATE without usable c=/m= SDP: callId=$requestCallId cseq=$requestCseq")
            return 488
        }

        val rtpRemote = sdpConnectionData.split(" ").getOrNull(2)
        val rtpRemoteAddr = rtpRemote?.let { InetAddress.getByName(it) }
        val mediaParts = sdpMedia.trim().split("\\s+".toRegex())
        val rtpRemotePort = mediaParts.getOrNull(1)?.toIntOrNull()
        val offeredPayloads = mediaParts.drop(3).mapNotNull { it.toIntOrNull() }.toSet()

        if (rtpRemoteAddr == null || rtpRemotePort == null || offeredPayloads.isEmpty()) {
            Rlog.w(
                TAG,
                "Rejecting UPDATE with incomplete media address/payloads: " +
                    "callId=$requestCallId cseq=$requestCseq c=$sdpConnectionData m=$sdpMedia",
            )
            return 488
        }

        val attributes = sdp.filter { it.startsWith("a=") }.map { it.substring(2) }
        SipAudioCodecSdpLogger.logRemoteAudioCodecCandidates(
            tag = TAG,
            context = "remote SDP ${request.method} callId=${request.callIdOrEmpty()}",
            sdp = sdp,
        )

        fun trackRequirements(track: Int): String? {
            return attributes.firstOrNull { it.startsWith("fmtp:$track") }
        }

        fun lookTrackMatching(
            codec: String,
            additional: String = "",
            notAdditional: String = "",
        ): Pair<Int, String>? {
            val maps = attributes.filter { it.startsWith("rtpmap:") && it.contains(codec) }
            val matches = maps.mapNotNull { m ->
                val track = m.split("[: ]+".toRegex()).getOrNull(1)?.toIntOrNull()
                if (track != null && offeredPayloads.contains(track)) Pair(track, m) else null
            }
            val sorted = matches.sortedBy { m ->
                val fmtp = trackRequirements(m.first).orEmpty()
                when {
                    // Our RTP encoder currently sends AMR-NB bandwidth-efficient frames.
                    // SDP without octet-align defaults to octet-align=0, so prefer that
                    // over octet-align=1 when carriers offer both forms in UPDATE.
                    codec.startsWith("AMR") && fmtp.contains("octet-align=1", ignoreCase = true) -> 100
                    codec.startsWith("AMR") && fmtp.isEmpty() -> 0
                    notAdditional.isNotEmpty() && fmtp.contains(notAdditional, ignoreCase = true) -> 90
                    additional.isNotEmpty() && fmtp.contains(additional, ignoreCase = true) -> 0
                    else -> 10
                }
            }
            Rlog.d(TAG, "UPDATE matching $codec offered=$offeredPayloads got=$sorted")
            return sorted.firstOrNull()
        }

        // Keep the selected speech payload first in SDP answers. Sorting payload IDs can
        // put telephone-event before AMR-WB, e.g. m=audio ... 96 104, which some
        // IMS cores reject as an offer/answer error during precondition UPDATE.
        val selectedAudioCodec = call.audioCodec
        val amr = lookTrackMatching(SipAudioCodecNegotiator.speechCodecRtpmapName(selectedAudioCodec), notAdditional = "octet-align=1")
        if (amr == null) {
            Rlog.w(TAG, "Rejecting UPDATE: no compatible ${SipAudioCodecNegotiator.speechCodecRtpmapName(selectedAudioCodec)} payload in offer callId=$requestCallId offered=$offeredPayloads")
            return 488
        }
        val (amrTrack, amrTrackDesc) = amr
        val amrFmtpAnswer = trackRequirements(amrTrack)
            ?: SipAudioCodecNegotiator.defaultSpeechFmtpAnswer(amrTrack, selectedAudioCodec)

        val dtmf = lookTrackMatching(SipAudioCodecNegotiator.telephoneEventRtpmapName(selectedAudioCodec))
        if (dtmf == null) {
            Rlog.w(TAG, "Rejecting UPDATE: no compatible ${SipAudioCodecNegotiator.telephoneEventRtpmapName(selectedAudioCodec)} payload in offer callId=$requestCallId offered=$offeredPayloads")
            return 488
        }
        val (dtmfTrack, dtmfTrackDesc) = dtmf

        try {
            if (!call.rtpSocket.isConnected ||
                call.rtpSocket.inetAddress != rtpRemoteAddr ||
                call.rtpSocket.port != rtpRemotePort) {
                call.rtpSocket.connect(rtpRemoteAddr, rtpRemotePort)
                Rlog.d(
                    TAG,
                    "UPDATE connected RTP socket to ${rtpRemoteAddr}:${rtpRemotePort} " +
                        "local=${call.rtpSocket.localAddress}:${call.rtpSocket.localPort} callId=$requestCallId",
                )
            }
        } catch (t: Throwable) {
            Rlog.w(TAG, "Failed to connect RTP socket from UPDATE to ${rtpRemoteAddr}:${rtpRemotePort} callId=$requestCallId", t)
        }

        val allTracks = listOf(amrTrack, dtmfTrack)
        val ipType = if (socket.gLocalAddr() is Inet6Address) "IP6" else "IP4"
        val owner = request.destination.substringAfter("sip:").substringBefore("@").ifBlank { "-" }
        val sdpVersion = call.localSdpVersion.incrementAndGet()
        val remoteMaxptime = attributes.firstOrNull { it.startsWith("maxptime:") } ?: "maxptime:240"
        val sdpBandwidthAs = SipAudioCodecNegotiator.sdpBandwidthAsKbps(selectedAudioCodec)

        val answerSdpLines = listOf(
            "v=0",
            "o=$owner 1 $sdpVersion IN $ipType ${socket.gLocalAddr().hostAddress}",
            "s=phh voice call",
            "c=IN $ipType ${socket.gLocalAddr().hostAddress}",
            "b=AS:$sdpBandwidthAs",
            "b=RS:0",
            "b=RR:0",
            "t=0 0",
            "m=audio ${call.rtpSocket.localPort} RTP/AVP ${allTracks.joinToString(" ")}",
            "b=AS:$sdpBandwidthAs",
            "b=RS:0",
            "b=RR:0",
            "a=$amrTrackDesc",
            "a=ptime:20",
            "a=$remoteMaxptime",
            "a=$dtmfTrackDesc",
            "a=$amrFmtpAnswer",
            "a=fmtp:$dtmfTrack 0-15",
            "a=curr:qos local sendrecv",
            "a=curr:qos remote sendrecv",
            "a=des:qos mandatory local sendrecv",
            "a=des:qos mandatory remote sendrecv",
            "a=conf:qos remote sendrecv",
            "a=sendrecv",
        )
        val answerSdp = answerSdpLines.joinToString("\r\n").toByteArray(Charsets.US_ASCII)

        currentCall = call.copy(
            amrTrack = amrTrack,
            amrTrackDesc = amrTrackDesc,
            dtmfTrack = dtmfTrack,
            dtmfTrackDesc = dtmfTrackDesc,
            rtpRemoteAddr = rtpRemoteAddr,
            rtpRemotePort = rtpRemotePort,
            sdp = answerSdp,
            remoteContact = request.headers["contact"]?.getOrNull(0)
                ?.let { extractDestinationFromContact(it) }
                ?: call.remoteContact,
        )

        val reply = SipResponse(
            statusCode = 200,
            statusString = "OK",
            headersParam = request.headers.filter { (k, _) ->
                k in listOf("cseq", "via", "from", "to", "call-id")
            } + """
                Content-Type: application/sdp
                Supported: 100rel, replaces, timer
                Require: precondition
                Call-ID: ${currentCall!!.callIdOrEmpty()}
            """.toSipHeadersMap(),
            body = answerSdp,
        )
        writeUpdateReply(reply)

        if (!call.outgoing) {
            val myHeaders2 = call.callHeaders - "rseq" - "content-type" - "require"
            val msg2 = SipResponse(
                statusCode = 180,
                statusString = "Ringing",
                headersParam = myHeaders2,
            )
            Rlog.d(TAG, "Sending $msg2")
            synchronized(updateResponseWriter) {
                updateResponseWriter.write(msg2.toByteArray())
            }
        }

        return 0
    }

    private fun remoteEndExtras(
        callId: String,
        terminatedCall: Call?,
        isBye: Boolean,
    ): Map<String, String> = SipRemoteEndExtrasBuilder.build(
        logTag = TAG,
        callId = callId,
        isBye = isBye,
        isOutgoingCall = terminatedCall?.outgoing == true,
        outgoingConnectedNotified = terminatedCall?.outgoingConnectedNotified?.get() == true,
    )

    fun handleCancel(request: SipRequest): Int {
        val callId = request.callIdOrEmpty()
        val isCancel = request.method == SipMethod.CANCEL
        val isBye = request.method == SipMethod.BYE

        // RFC 3261 §9.2: CANCEL has no effect if we already sent a final response
        // for the INVITE. Reply 200 OK to the CANCEL transaction, but keep the
        // dialog/runtime alive until the final ACK or a real BYE arrives.
        //
        // Some networks can race a late CANCEL against our final 200 OK. Clearing
        // currentCall here makes the local UI drop immediately even though the
        // dialog has already been answered and the remote side will usually send
        // a BYE to terminate the established dialog.
        if (isCancel && incomingFinalResponseSent.get()) {
            Rlog.d(TAG, "CANCEL received after final 200 OK was sent — replying 200 to CANCEL and keeping answered dialog")
            val toOverride = currentCall?.callHeaders?.get("to") ?: request.headers["to"]
            val responseHeaders = responseHeadersFromRequest(
                request,
                toOverride = toOverride,
                extra = "Content-Length: 0".toSipHeadersMap(),
            )
            val response = SipResponse(
                statusCode = 200,
                statusString = "OK",
                headersParam = responseHeaders,
                autofill = false
            )
            Rlog.d(TAG, "Sending explicit 200 OK to late CANCEL: $response")
            val cancelResponseWriter = dispatcher.writerForCallId(callId) ?: currentCall?.incomingResponseWriter ?: socket.gWriter()
            synchronized(cancelResponseWriter) { cancelResponseWriter.write(response.toByteArray()) }

            return 0
        }

        stopCallRuntime("call cleanup")
        prAckWaitTracker.clearAndNotifyAll()

        Rlog.d(TAG, "Cancelled call $callId method=${request.method}")

        if (isCancel) {
            val cancelResponseWriter = currentCall?.incomingResponseWriter ?: dispatcher.writerForCallId(callId) ?: socket.gWriter()
            val toOverride = currentCall?.callHeaders?.get("to") ?: request.headers["to"]

            // RFC 3261: CANCEL is its own transaction. Reply 200 OK to the CANCEL,
            // then terminate the original INVITE transaction with 487 using CSeq: INVITE.
            // Do not let parseMessage emit an extra generic 200 OK with a different To tag.
            val cancelOkHeaders = responseHeadersFromRequest(
                request,
                toOverride = toOverride,
                extra = "Content-Length: 0".toSipHeadersMap(),
            )
            val cancelOk = SipResponse(
                statusCode = 200,
                statusString = "OK",
                headersParam = cancelOkHeaders,
                autofill = false
            )
            Rlog.d(TAG, "Sending 200 OK to CANCEL $cancelOk")
            synchronized(cancelResponseWriter) { cancelResponseWriter.write(cancelOk.toByteArray()) }

            val originalInviteCseq = request.headers["cseq"]?.getOrNull(0)
                ?.replace(Regex("\\bCANCEL\\b", RegexOption.IGNORE_CASE), "INVITE")
                ?: "1 INVITE"
            val inviteTerminatedHeaders = responseHeadersFromRequest(
                request,
                toOverride = toOverride,
                extra = """
                    CSeq: $originalInviteCseq
                    Content-Length: 0
                    """.toSipHeadersMap(),
            )
            val inviteTerminated = SipResponse(
                statusCode = 487,
                statusString = "Request Terminated",
                headersParam = inviteTerminatedHeaders,
                autofill = false
            )
            Rlog.d(TAG, "Sending 487 for cancelled INVITE $inviteTerminated")
            synchronized(cancelResponseWriter) { cancelResponseWriter.write(inviteTerminated.toByteArray()) }

            rememberTerminatedIncomingCall(callId, "remote CANCEL")
            currentCall = null
            clearPendingOutgoingInvite(callId, closeRtpSocket = false, reason = "remote CANCEL")
            onCancelledCall?.invoke(Object(), "", mapOf("call-id" to callId))
            return 0
        } else if (!isBye) {
            Rlog.w(TAG, "handleCancel called for unexpected method ${request.method}")
        }

        if (currentCall?.outgoing == false) rememberTerminatedIncomingCall(callId, "remote ${request.method}")
        val terminatedCall = currentCall
        currentCall = null
        clearPendingOutgoingInvite(callId, closeRtpSocket = false, reason = "remote ${request.method}")
        val cancelExtras = remoteEndExtras(callId, terminatedCall, isBye)
        onCancelledCall?.invoke(Object(), "", cancelExtras)
        return 200
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

    private data class PendingOutgoingInvite(
        val callId: String,
        val destination: String,
        val headers: SipHeadersMap,
        val rtpSocket: DatagramSocket,
        val body: ByteArray,
        val retriedAfter422: AtomicBoolean = AtomicBoolean(false),
        val retriedAfterIllegalSdp: AtomicBoolean = AtomicBoolean(false),
        val cancelSent: AtomicBoolean = AtomicBoolean(false),
    )


    // illegal SDP conservative retry: retry once only when the SBC explicitly rejects the SDP body.
    private fun responseWarnsIllegalSdp(response: SipResponse): Boolean {
        if (response.statusCode != 400) return false
        val warningValues = response.headers.entries
            .filter { it.key.equals("warning", ignoreCase = true) }
            .flatMap { it.value }
        return warningValues.any { warning ->
            warning.contains("SDP is illegal", ignoreCase = true) ||
                warning.contains("illegal SDP", ignoreCase = true)
        }
    }

    private fun removeSipHeaderToken(
        headers: SipHeadersMap,
        headerName: String,
        token: String,
    ): SipHeadersMap {
        val values = headers.entries
            .filter { it.key.equals(headerName, ignoreCase = true) }
            .flatMap { it.value }
        if (values.isEmpty()) return headers

        val filteredValues = values.mapNotNull { value ->
            val keptTokens = value.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals(token, ignoreCase = true) }
            if (keptTokens.isEmpty()) null else keptTokens.joinToString(", ")
        }
        val strippedHeaders = headers.filterKeys { !it.equals(headerName, ignoreCase = true) }
        return if (filteredValues.isEmpty()) {
            strippedHeaders
        } else {
            strippedHeaders + (headerName.lowercase() to filteredValues)
        }
    }

    private fun outgoingInviteIllegalSdpRetryHeaders(
        headers: SipHeadersMap,
        retryCseq: Int,
    ): SipHeadersMap {
        var retryHeaders = headers.filterKeys {
            !it.equals("cseq", ignoreCase = true) &&
                !it.equals("content-length", ignoreCase = true)
        } + ("cseq" to listOf("$retryCseq INVITE"))
        retryHeaders = removeSipHeaderToken(retryHeaders, "supported", "precondition")
        retryHeaders = removeSipHeaderToken(retryHeaders, "require", "precondition")
        return retryHeaders
    }

    private fun conservativeAmrNbOutgoingInviteSdpBody(originalBody: ByteArray): ByteArray {
        val originalLines = originalBody
            .toString(Charsets.US_ASCII)
            .split(Regex("\r?\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        fun line(prefix: String): String? = originalLines.firstOrNull { it.startsWith(prefix) }
        val localHost = socket.gLocalAddr().hostAddress ?: "0.0.0.0"
        val ipType = if (localHost.contains(':')) "IP6" else "IP4"
        val audioPort = line("m=audio ")?.split(Regex("\\s+"))?.getOrNull(1) ?: "0"

        val retryLines = listOf(
            line("v=") ?: "v=0",
            line("o=") ?: "o=- 1 2 IN $ipType $localHost",
            line("s=") ?: "s=phh voice call",
            line("c=") ?: "c=IN $ipType $localHost",
            "b=AS:38",
            "b=RS:0",
            "b=RR:0",
            line("t=") ?: "t=0 0",
            "m=audio $audioPort RTP/AVP 97 100",
            "b=AS:38",
            "b=RS:0",
            "b=RR:0",
            "a=ptime:20",
            "a=maxptime:240",
            "a=rtpmap:97 AMR/8000/1",
            "a=fmtp:97 mode-change-capability=2;octet-align=0;max-red=0",
            "a=rtpmap:100 telephone-event/8000",
            "a=fmtp:100 0-15",
            "a=sendrecv",
        )
        return (retryLines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.US_ASCII)
    }

    private fun retryOutgoingInviteAfterIllegalSdp(
        pending: PendingOutgoingInvite,
        response: SipResponse,
        outgoingDialogNextCseq: AtomicInteger,
    ): Boolean {
        if (!responseWarnsIllegalSdp(response)) return false
        if (pending.cancelSent.get()) {
            Rlog.w(TAG, "Not retrying outgoing INVITE after illegal SDP because CANCEL was already sent callId=${pending.callId}")
            return false
        }
        if (!pending.retriedAfterIllegalSdp.compareAndSet(false, true)) {
            Rlog.w(TAG, "Not retrying outgoing INVITE after illegal SDP twice callId=${pending.callId}")
            return false
        }

        val oldCseqHeader = pending.headers.entries
            .firstOrNull { it.key.equals("cseq", ignoreCase = true) }
            ?.value
            ?.getOrNull(0)
            ?: "1 INVITE"
        val oldCseq = oldCseqHeader.substringBefore(" ").trim().toIntOrNull() ?: 1
        val retryCseq = oldCseq + 1
        val retryBody = conservativeAmrNbOutgoingInviteSdpBody(pending.body)
        val retryHeaders = outgoingInviteIllegalSdpRetryHeaders(pending.headers, retryCseq)
        val retryInvite = SipRequest(
            SipMethod.INVITE,
            pending.destination,
            retryHeaders,
            retryBody,
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
            "Retrying outgoing INVITE after 400 illegal SDP with conservative AMR-NB offer " +
                "callId=${pending.callId} oldCseq=$oldCseq retryCseq=$retryCseq " +
                "oldBytes=${pending.body.size} retryBytes=${retryBody.size} " +
                imsDualSimDebugContext(),
        )
        writeSipBytesWithFlush(socket.gWriter(), "SipHandler illegal-sdp retry INVITE", retryInvite.toByteArray())
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
            Rlog.w(TAG, "Not retrying outgoing INVITE after 422 twice callId=${pending.callId}")
            return false
        }

        val retryInvite = SipRequest(
            SipMethod.INVITE,
            pending.destination,
            retry.headers,
            pending.body,
        )

        pendingOutgoingInvite = pending.copy(headers = retryInvite.headers)
        val desiredNextCseq = retry.cseqNumber + 1
        while (true) {
            val oldNextCseq = outgoingDialogNextCseq.get()
            if (oldNextCseq >= desiredNextCseq ||
                outgoingDialogNextCseq.compareAndSet(oldNextCseq, desiredNextCseq)
            ) break
        }

        Rlog.w(
            TAG,
            "Retrying outgoing INVITE after 422 with Min-SE=${retry.minSeSeconds} " +
                "Session-Expires=${retry.sessionExpiresSeconds} " +
                "callId=${pending.callId} cseq=${retry.cseqNumber}",
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
    fun callEncodeThread(
        incomingMicStartDelayMs: Long = 0L,
        reason: String = "default",
        callSnapshot: Call? = null,
    ) {
        val call = callSnapshot ?: currentCall
        if (call == null) {
            Rlog.w(TAG, "callEncodeThread: no currentCall; not starting encoder reason=$reason")
            return
        }
        val audioCodec = call.audioCodec
        val callId = call.callIdOrEmpty()
        val gen = callGeneration.get()
        thread {
            rtpSequenceNumber.set(0)
            rtpTimestampSamples.set(0)
            rtpDtmfTimestampSamples.set(0)
            Rlog.d(TAG, "Encode thread started: codec=${audioCodec.name}/${audioCodec.sampleRate} callId=$callId amrTrack=${call.amrTrack} remote=${call.rtpRemoteAddr}:${call.rtpRemotePort} gen=$gen")
            val encoder = SipAudioCodecFactory.createStartedEncoder(
                audioCodec = audioCodec,
            )

            if (!SipUplinkSilencePacer.sendUntilCallStarted(
                logTag = TAG,
                callStarted = callStarted,
                callStopped = callStopped,
                callGeneration = callGeneration,
                generation = gen,
                nextSequenceNumber = { rtpSequenceNumber.getAndIncrement() },
                nextTimestamp = { rtpTimestampSamples.getAndAdd(audioCodec.rtpTimestampStep) },
                totalPacketsSent = { rtpSequenceNumber.get() },
                sendPacket = { sequenceNumber, timestamp ->
                    val sendCall = currentCall?.takeIf { it.callIdOrEmpty() == callId } ?: call
                    SipUplinkSilenceRtpSender.sendNoDataPacket(
                        logTag = TAG,
                        audioCodec = audioCodec,
                        payloadType = sendCall.amrTrack,
                        sequenceNumber = sequenceNumber,
                        timestamp = timestamp,
                        rtpSocket = sendCall.rtpSocket,
                        remoteAddr = sendCall.rtpRemoteAddr,
                        remotePort = sendCall.rtpRemotePort,
                        label = "RTP packet #$sequenceNumber",
                    )
                },
                cleanupOnExit = {
                    encoder.stop()
                    encoder.release()
                },
            )) return@thread
            if (!call.outgoing && incomingMicStartDelayMs > 0L) {
                if (!SipUplinkIncomingMicSettlePacer.delayBeforeMicStart(
                    logTag = TAG,
                    delayMs = incomingMicStartDelayMs,
                    reason = reason,
                    callStopped = callStopped,
                    callGeneration = callGeneration,
                    generation = gen,
                    nextSequenceNumber = { rtpSequenceNumber.getAndIncrement() },
                    nextTimestamp = { rtpTimestampSamples.getAndAdd(audioCodec.rtpTimestampStep) },
                    sendPacket = { sequenceNumber, timestamp ->
                        val sendCall = currentCall?.takeIf { it.callIdOrEmpty() == callId } ?: call
                        SipUplinkSilenceRtpSender.sendNoDataPacket(
                            logTag = TAG,
                            audioCodec = audioCodec,
                            payloadType = sendCall.amrTrack,
                            sequenceNumber = sequenceNumber,
                            timestamp = timestamp,
                            rtpSocket = sendCall.rtpSocket,
                            remoteAddr = sendCall.rtpRemoteAddr,
                            remotePort = sendCall.rtpRemotePort,
                            label = "incoming RTP settle silence #$sequenceNumber",
                        )
                    },
                    cleanupOnExit = {
                        try { encoder.stop() } catch (_: Throwable) { }
                        try { encoder.release() } catch (_: Throwable) { }
                    },
                )) return@thread
            }

            val capture = SipUplinkAudioCaptureStarter.start(
                logTag = TAG,
                context = ctxt,
                audioCodec = audioCodec,
                encoder = encoder,
            ) ?: return@thread
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
                generation = gen,
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
                generation = gen,
                totalPacketsSent = rtpSequenceNumber.get(),
                previousAudioMode = prevAudioMode,
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
            Rlog.d(TAG, "Not notifying outgoing connected for stale call: callId=$callId active=$activeCallId reason=$reason")
            return
        }

        if (!callStarted.get()) {
            Rlog.d(TAG, "Outgoing RTP seen before final answer; wait before connected notify callId=$callId reason=$reason")
            return
        }

        if (!call.outgoingRtpReceived.get()) {
            Rlog.d(TAG, "Outgoing final answer received but no post-answer remote RTP yet; keeping Android call in dialing state callId=$callId reason=$reason")
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

        Rlog.d(TAG, "Outgoing call connected after remote RTP: callId=$callId reason=$reason")
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

                Rlog.w(TAG, "No post-answer RTP within ${timeoutMs}ms for outgoing call; terminating no-media dialog as network reject callId=$callId")
                callId?.let { outgoingConnectedCallIds.remove(it) }
                stopCallRuntime("post-answer RTP timeout")
                try {
                    sendByeForCall(activeCall)
                } catch (t: Throwable) {
                    Rlog.w(TAG, "Failed to send BYE for outgoing no-media timeout callId=$callId", t)
                }
                currentCall = null
                clearPendingOutgoingInvite(callId, closeRtpSocket = false, reason = "post-answer RTP timeout")
                onCancelledCall?.invoke(
                    Object(),
                    "",
                    mapOf(
                        "call-id" to callId,
                        "statusCode" to "480",
                        "statusString" to "No post-answer RTP",
                        "remoteNoMediaRelease" to "true",
                    )
                )
            } catch (t: Throwable) {
                Rlog.e(TAG, "Outgoing post-answer RTP timeout failed callId=$callId", t)
            }
        }
    }

    private fun completeIncomingPreconditionAnswerSdp(answerSdp: ByteArray, callId: String): ByteArray {
        val lines = answerSdp
            .toString(Charsets.UTF_8)
            .split("[\r\n]+".toRegex())
            .filter { it.isNotBlank() }

        val hasPrecondition = lines.any { line ->
            line.startsWith("a=curr:qos", ignoreCase = true) ||
                line.startsWith("a=des:qos", ignoreCase = true) ||
                line.startsWith("a=conf:qos", ignoreCase = true)
        }
        if (!hasPrecondition) return answerSdp

        val rewritten = lines.map { line ->
            when {
                line.startsWith("a=curr:qos local", ignoreCase = true) -> "a=curr:qos local sendrecv"
                line.startsWith("a=curr:qos remote", ignoreCase = true) -> "a=curr:qos remote sendrecv"
                line.startsWith("a=des:qos optional local", ignoreCase = true) -> "a=des:qos mandatory local sendrecv"
                line.startsWith("a=des:qos optional remote", ignoreCase = true) -> "a=des:qos mandatory remote sendrecv"
                line.startsWith("a=des:qos mandatory local", ignoreCase = true) -> "a=des:qos mandatory local sendrecv"
                line.startsWith("a=des:qos mandatory remote", ignoreCase = true) -> "a=des:qos mandatory remote sendrecv"
                line.startsWith("a=conf:qos remote", ignoreCase = true) -> "a=conf:qos remote sendrecv"
                line.equals("a=inactive", ignoreCase = true) -> "a=sendrecv"
                line.equals("a=sendonly", ignoreCase = true) -> "a=sendrecv"
                line.equals("a=recvonly", ignoreCase = true) -> "a=sendrecv"
                else -> line
            }
        }.let { mapped ->
            val withConf = if (mapped.any { it.startsWith("a=conf:qos remote", ignoreCase = true) }) {
                mapped
            } else {
                mapped + "a=conf:qos remote sendrecv"
            }
            if (withConf.any { it.equals("a=sendrecv", ignoreCase = true) }) {
                withConf
            } else {
                withConf + "a=sendrecv"
            }
        }

        if (rewritten != lines) {
            Rlog.d(TAG, "Completing incoming final 200 OK precondition SDP: callId=$callId")
        }
        return (rewritten.joinToString("\r\n") + "\r\n").toByteArray(Charsets.US_ASCII)
    }

    fun acceptCall() {
        thread {
            var call = currentCall
            if (call == null || call.outgoing) {
                Rlog.w(TAG, "acceptCall without valid incoming currentCall: $call")
                return@thread
            }

            val acceptedCallId = call.callIdOrEmpty()
            if (!delayIncomingAcceptAfterRecentImsAccessChange(acceptedCallId)) {
                return@thread
            }

            call = currentCall
            if (call == null || call.outgoing || call.callIdOrEmpty() != acceptedCallId) {
                Rlog.w(
                    TAG,
                    "acceptCall aborted after IMS access guard because current call changed: " +
                        "acceptedCallId=$acceptedCallId current=${call?.callIdOrEmpty()} outgoing=${call?.outgoing}",
                )
                return@thread
            }

            // S9/O2 test mode: never block accept on pending incoming PRACK state.
            // The network currently does not PRACK our reliable incoming 183, so
            // waiting here makes the remote side ring until timeout.
            prAckWaitTracker.dropStaleBeforeAccept(TAG)

            Rlog.d(TAG, "Accepting call")
            val omitFinalSdp = call.hasEarlyMedia
            if (!omitFinalSdp) {
                val finalIncomingSdp = completeIncomingPreconditionAnswerSdp(call.sdp, acceptedCallId)
                if (!finalIncomingSdp.contentEquals(call.sdp)) {
                    call = call.copy(sdp = finalIncomingSdp)
                    currentCall = call
                }
            } else {
                Rlog.d(
                    TAG,
                    "Omitting SDP from final incoming 200 OK because reliable provisional/UPDATE offer-answer already completed " +
                        "callId=$acceptedCallId",
                )
            }

            val myHeaders = call.callHeaders
            val finalBody = if (!omitFinalSdp) call.sdp else ByteArray(0)
            val finalSdpHeaders = if (!omitFinalSdp) {
                """
                Content-Type: application/sdp
                Content-Length: ${finalBody.size}
                """.toSipHeadersMap()
            } else {
                "Content-Length: 0".toSipHeadersMap()
            }
            val myHeaders3 =
                myHeaders - "rseq" - "security-verify" - "p-access-network-info" - "content-type" - "content-length" +
                    """
                    Session-Expires: 1800;refresher=uas
                    Contact: ${call.callHeaders["contact"]!!.first()}
                    """.toSipHeadersMap() +
                    finalSdpHeaders

            val msg3 =
                SipResponse(
                    statusCode = 200,
                    statusString = "OK",
                    headersParam = myHeaders3,
                    body = finalBody,
                    autofill = false
                )
            val responseWriter = call.incomingResponseWriter ?: socket.gWriter()
            val responseBytes = msg3.toByteArray()
            Rlog.d(TAG, "Sending $msg3 via incomingResponseWriter=${call.incomingResponseWriter != null}")
            incomingFinalResponseSent.set(true)
            incomingAcceptedAwaitingAck.set(true)
            if (!writeSipBytes(responseWriter, responseBytes, "incoming INVITE final 200 OK callId=$acceptedCallId")) {
                    incomingFinalResponseSent.set(false)
                    incomingAcceptedAwaitingAck.set(false)
                    incomingHangupAfterAck.set(false)
                    onCancelledCall?.invoke(Object(), "", mapOf("call-id" to acceptedCallId))
                    return@thread
                }
            incomingFinalResponseSent.set(true)
            incomingAcceptedAwaitingAck.set(true)
            incomingHangupAfterAck.set(false)
            if (threadsStarted.compareAndSet(false, true)) {
                Rlog.d(TAG, "Prewarming incoming media threads after final 200 OK while waiting for ACK; delaying mic open after ACK")
                callDecodeThread()
                callEncodeThread(
                    incomingMicStartDelayMs = 250L,
                    reason = "incoming ACK audio route settle",
                    callSnapshot = call,
                )
            } else {
                Rlog.d(TAG, "Incoming media threads already started while accepting call")
            }

            // RFC 3261: 2xx responses to INVITE are end-to-end and must be retransmitted
            // by the UAS core until the matching ACK arrives. This is also useful here as a
            // diagnostic: if the first 200 OK is lost/ignored on the IMS TCP flow, repeated
            // 200 OKs should make the missing-ACK problem visible in the log/network trace.
            thread(name = "PhhIncoming2xxRetransmit") {
                var delayMs = 500L
                var elapsedMs = 0L
                while (incomingAcceptedAwaitingAck.get() && elapsedMs < 32000L) {
                    Thread.sleep(delayMs)
                    elapsedMs += delayMs
                    val stillSameCall = currentCall?.callIdOrNull() == acceptedCallId
                    if (!incomingAcceptedAwaitingAck.get() || !stillSameCall) break
                    Rlog.w(TAG, "Retransmitting incoming 200 OK waiting for ACK callId=$acceptedCallId elapsed=${elapsedMs}ms")
                    val retransmitWriter =
                        currentCall?.takeIf { it.callIdOrNull() == acceptedCallId }?.incomingResponseWriter
                            ?: responseWriter
                    if (!writeSipBytes(retransmitWriter, responseBytes, "incoming INVITE final 200 OK retransmit callId=$acceptedCallId elapsed=${elapsedMs}ms")) {
                        Rlog.w(TAG, "Stopping incoming 200 OK retransmit after write failure callId=$acceptedCallId elapsed=${elapsedMs}ms")
                        incomingAcceptedAwaitingAck.set(false)
                        incomingHangupAfterAck.set(false)
                        break
                    }
                    delayMs = (delayMs * 2).coerceAtMost(4000L)
                }
                if (incomingAcceptedAwaitingAck.get()) {
                    Rlog.w(TAG, "Incoming accepted call still has no ACK after ${elapsedMs}ms; clearing pending accepted state callId=$acceptedCallId")
                    incomingAcceptedAwaitingAck.set(false)
                    incomingHangupAfterAck.set(false)
                    if (currentCall?.callIdOrNull() == acceptedCallId && !callStarted.get()) {
                        stopCallRuntime("call cleanup")
                        rememberTerminatedIncomingCall(acceptedCallId, "incoming ACK timeout")
                        currentCall = null
                        onCancelledCall?.invoke(Object(), "", mapOf("call-id" to acceptedCallId))
                    }
                }
            }

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
                Rlog.w(TAG, "rejectCall without valid incoming currentCall: $call")
                return@thread
            }
            val rejectedCallId = call.callIdOrEmpty()
            rememberTerminatedIncomingCall(rejectedCallId, "local reject")
            val myHeaders = call.callHeaders - "rseq" - "require" - "content-type" - "p-access-network-info" +
                "Content-Length: 0".toSipHeadersMap()
            val msg =
                SipResponse(
                    statusCode = 603,
                    statusString = "Decline",
                    headersParam = myHeaders,
                    autofill = false
                )
            val responseWriter = call.incomingResponseWriter ?: dispatcher.writerForCallId(rejectedCallId) ?: socket.gWriter()
            Rlog.d(TAG, "Sending $msg via incomingResponseWriter=${call.incomingResponseWriter != null}")
            writeSipBytesWithFlush(responseWriter, "SipHandler msg", msg.toByteArray())

            stopCallRuntime("call cleanup")
            incomingFinalResponseSent.set(false)
            incomingAcceptedAwaitingAck.set(false)
            incomingHangupAfterAck.set(false)
            currentCall = null
            onCancelledCall?.invoke(Object(), "", mapOf(
                "call-id" to rejectedCallId,
                "statusCode" to "603",
                "statusString" to "Decline",
                "localReject" to "true",
            ))
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

        Rlog.d(TAG, "Clearing pending outgoing INVITE callId=${pending.callId} closeRtpSocket=$closeRtpSocket reason=$reason")
        pendingOutgoingInvite = null
        if (closeRtpSocket && currentCall?.rtpSocket !== pending.rtpSocket) {
            try {
                pending.rtpSocket.close()
            } catch (t: Throwable) {
                Rlog.d(TAG, "Closing pending outgoing RTP socket failed", t)
            }
        }
    }

    private fun sendCancelForPendingOutgoingInvite(pending: PendingOutgoingInvite, reason: String): Boolean {
        if (!pending.cancelSent.compareAndSet(false, true)) {
            Rlog.d(TAG, "CANCEL already sent for pending outgoing INVITE callId=${pending.callId} reason=$reason")
            return false
        }

        val inviteCseqNumber = pending.headers["cseq"]?.getOrNull(0)?.substringBefore(" ") ?: "1"
        val cancellableHeaders = pending.headers.filter { (k, _) ->
            k in setOf(
                "via",
                "route",
                "from",
                "to",
                "call-id",
                "max-forwards",
                "user-agent",
                "p-access-network-info",
                "security-verify",
                "require",
                "proxy-require",
            )
        }
        val cancelHeaders = cancellableHeaders - "cseq" - "content-length" - "content-type" +
            """
            CSeq: $inviteCseqNumber CANCEL
            Content-Length: 0
            """.toSipHeadersMap()
        val cancel = SipRequest(
            SipMethod.CANCEL,
            pending.destination,
            headersParam = cancelHeaders,
        )
        Rlog.d(TAG, "Sending CANCEL for pending outgoing INVITE callId=${pending.callId} reason=$reason $cancel")
        writeSipBytesWithFlush(socket.gWriter(), "SipHandler cancel", cancel.toByteArray())

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
            reason = "local CANCEL sent: $reason",
        )
        return true
    }

    private fun sendByeForCall(call: Call) {
        val byeHeaders = localDialogHeadersForRequest(call, SipMethod.BYE)
        val bye = SipRequest(
            SipMethod.BYE,
            call.remoteContact,
            headersParam = byeHeaders
        )
        Rlog.d(TAG, "Sending BYE $bye")
        writeSipBytesWithFlush(socket.gWriter(), "SipHandler bye", bye.toByteArray())
    }

    fun terminateCall() {
        val call = currentCall
        val pendingOutgoing = pendingOutgoingInvite

        if (call == null) {
            if (pendingOutgoing != null) {
                Rlog.w(TAG, "Local hangup while outgoing INVITE is still pending; sending CANCEL callId=${pendingOutgoing.callId}")
                stopCallRuntime("local terminate")
                sendCancelForPendingOutgoingInvite(pendingOutgoing, "local hangup before dialog")
                onCancelledCall?.invoke(Object(), "", mapOf("call-id" to pendingOutgoing.callId))
                return
            }

            Rlog.w(TAG, "terminateCall without currentCall or pending outgoing INVITE")
            return
        }

        callStopped.set(true)

        if (call.outgoing && !callStarted.get()) {
            if (pendingOutgoing != null && pendingOutgoing.callId == call.callIdOrNull()) {
                Rlog.w(TAG, "Local hangup before outgoing INVITE final answer; sending CANCEL callId=${pendingOutgoing.callId}")
                sendCancelForPendingOutgoingInvite(pendingOutgoing, "local hangup before final INVITE answer")
                currentCall = null
                onCancelledCall?.invoke(Object(), "", mapOf("call-id" to pendingOutgoing.callId))
                return
            }
            Rlog.w(TAG, "Outgoing call not confirmed yet but no pending INVITE exists; falling back to BYE")
        }

        if (!call.outgoing && incomingFinalResponseSent.get() && !callStarted.get()) {
            Rlog.w(TAG, "Local hangup before incoming ACK; deferring BYE until ACK and keeping 200 OK retransmission active")
            incomingHangupAfterAck.set(true)
            Rlog.d(TAG, "Keeping accepted pre-ACK incoming Call-ID live for final 200 OK retransmits")
            onCancelledCall?.invoke(Object(), "", emptyMap())
            return
        }

        sendByeForCall(call)
        if (!call.outgoing) {
            rememberTerminatedIncomingCall(call.callIdOrEmpty(), "local BYE")
            currentCall = null
        } else {
            val outgoingByeCallId = call.callIdOrNull()
            Rlog.d(TAG, "Keeping outgoing dialog until BYE transaction completes callId=$outgoingByeCallId")
            myHandler.postDelayed({
                if (currentCall?.outgoing == true &&
                    currentCall?.callIdOrNull() == outgoingByeCallId &&
                    callStopped.get()
                ) {
                    Rlog.w(TAG, "Clearing outgoing dialog after BYE response timeout callId=$outgoingByeCallId")
                    currentCall = null
                }
            }, 4000L)
        }
        incomingAcceptedAwaitingAck.set(false)
        incomingHangupAfterAck.set(false)
        clearPendingOutgoingInvite(call.callIdOrNull(), closeRtpSocket = false, reason = "confirmed call terminated")
        onCancelledCall?.invoke(Object(), "", emptyMap())
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

    private fun normalizeSingTelStockOutgoingSdpLine(line: String): String {
        val wbRtpmap = if (
            line.startsWith("a=rtpmap:", ignoreCase = true) &&
                line.contains("AMR-WB/16000/1", ignoreCase = true)
        ) {
            line.replace("AMR-WB/16000/1", "AMR-WB/16000")
        } else {
            line
        }

        val normalizedFmtp = Regex(
            "^a=fmtp:(\\d+)\\s+.*mode-change-capability=2.*$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(wbRtpmap)?.let { match ->
            "a=fmtp:${match.groupValues[1]} max-red=0; mode-change-capability=2; octet-align=0"
        } ?: wbRtpmap

        return if (normalizedFmtp.equals("a=maxptime:240", ignoreCase = true)) {
            "a=maxptime:40"
        } else {
            normalizedFmtp
        }
    }


    fun call(phoneNumber: String) {
        thread {
            callStopped.set(false)
            callStarted.set(false)
            threadsStarted.set(false)
            callGeneration.incrementAndGet()
            clearPendingOutgoingInvite(closeRtpSocket = true, reason = "new outgoing call")

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
                return@thread
            }
            try {
                network.bindSocket(rtpSocket)
            } catch (t: Throwable) {
                Rlog.e(TAG, "Failed to bind outgoing RTP socket to IMS network", t)
                try { rtpSocket.close() } catch (_: Throwable) {}
                reconnectIms("outgoing RTP network.bindSocket failed")
                return@thread
            }
            rtpSocket.soTimeout = RTP_SOCKET_RECEIVE_TIMEOUT_MS
            // Connect later once the remote RTP address/port is known from SDP.
            Rlog.d(TAG, "RTP socket created for outgoing call: local=${rtpSocket.localAddress}:${rtpSocket.localPort} timeout=${rtpSocket.soTimeout}")

            val amrNbTrack = 97
            val amrWbTrack = 98
            val dtmfNbTrack = 100
            val dtmfWbTrack = 101
            val offerAmrWb = amrWbMediaCodecAvailable
            val allTracks = if (offerAmrWb) {
                listOf(amrWbTrack, amrNbTrack, dtmfWbTrack, dtmfNbTrack)
            } else {
                listOf(amrNbTrack, dtmfNbTrack)
            }
            val offerBandwidthAs = if (offerAmrWb) {
                SipAudioCodecNegotiator.sdpBandwidthAsKbps(SipAudioCodecs.AMR_WB)
            } else {
                SipAudioCodecNegotiator.sdpBandwidthAsKbps(SipAudioCodecs.AMR_NB)
            }
            Rlog.d(
                TAG,
                "Outgoing INVITE codec offer: offerAmrWb=$offerAmrWb " +
                    "tracks=$allTracks bandwidthAs=$offerBandwidthAs",
            )

            val ipType = if(localAddr is Inet6Address) "IP6" else "IP4"

            val sdpLines = mutableListOf(
               "v=0",
               "o=- 1 2 IN $ipType ${socket.gLocalAddr().hostAddress}",
               "s=phh voice call",
               "c=IN $ipType ${socket.gLocalAddr().hostAddress}",
               "b=AS:$offerBandwidthAs",
               "b=RS:0",
               "b=RR:0",
               "t=0 0",
               "m=audio ${rtpSocket.localPort} RTP/AVP ${allTracks.joinToString(" ")}",
               "b=AS:$offerBandwidthAs",
               "b=RS:0",
               "b=RR:0",
               "a=ptime:20",
               "a=maxptime:240",
           )
           if (offerAmrWb) {
               sdpLines += listOf(
                   "a=rtpmap:$amrWbTrack AMR-WB/16000",
                   "a=fmtp:$amrWbTrack octet-align=0;mode-change-capability=2;max-red=0",
                   "a=rtpmap:$dtmfWbTrack telephone-event/16000",
                   "a=fmtp:$dtmfWbTrack 0-15",
               )
           }
           sdpLines += listOf(
               "a=rtpmap:$amrNbTrack AMR/8000/1",
               "a=fmtp:$amrNbTrack mode-change-capability=2;octet-align=0;max-red=0",
               "a=rtpmap:$dtmfNbTrack telephone-event/8000",
               "a=fmtp:$dtmfNbTrack 0-15",
               "a=curr:qos local none",
               "a=curr:qos remote none",
               "a=des:qos optional local sendrecv",
               "a=des:qos optional remote sendrecv",
               "a=sendrecv",
           )
           /*
            * Some IMS SBCs are strict about SDP body framing and expect the
            * outgoing INVITE SDP body to end with a final CRLF after the last
            * SDP line, not only CRLF between lines.
            */
           val finalOutgoingSdpLines = if (isSingTelStockOutgoingCarrier()) {
               sdpLines.map { line -> normalizeSingTelStockOutgoingSdpLine(line) }
           } else {
               sdpLines
           }
           val sdp = (finalOutgoingSdpLines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.US_ASCII)
           /*
            * Keep the initial SingTel SDP offer compact enough to stay below the
            * carrier path's practical protected-request size limit. The normal
            * multi-codec/precondition offer is valid SIP/SDP, but is too large
            * for this path and gets dropped before any SIP response.
            */
           val singtelCompactInitialSdp = listOf(
               "v=0",
               "o=- 1 2 IN $ipType ${socket.gLocalAddr().hostAddress}",
               "s=-",
               "c=IN $ipType ${socket.gLocalAddr().hostAddress}",
               "t=0 0",
               "m=audio ${rtpSocket.localPort} RTP/AVP $amrNbTrack",
               "a=rtpmap:$amrNbTrack AMR/8000",
               "a=fmtp:$amrNbTrack octet-align=0",
               "a=ptime:20",
               "a=sendrecv",
           ).joinToString("\r\n")
               .plus("\r\n")
               .toByteArray(Charsets.US_ASCII)
           /*
            * SingTel requires a compact originating request. Use public SIP URI
            * addressing and a compact initial SDP offer below instead of the
            * generic TEL-URI/full-offer shape.
            */
           val outgoingInviteBody = if (isSingTelStockOutgoingCarrier()) {
               singtelCompactInitialSdp
           } else {
               sdp
           }

            val normalizedPhoneNumber = normalizeOutgoingDialTargetForTelUri(phoneNumber)
            val to = if (normalizedPhoneNumber.startsWith("+")) {
                // Global TEL URIs must stand on their own. Adding phone-context to +E.164
                // numbers makes some IMS cores drop the INVITE without any SIP response.
                "tel:$normalizedPhoneNumber"
            } else {
                "tel:$normalizedPhoneNumber;phone-context=ims.mnc$mnc.mcc$mcc.3gppnetwork.org"
            }
            Rlog.d(TAG, "Outgoing dial target raw=$phoneNumber normalized=$normalizedPhoneNumber uri=$to")
            val sipInstance = "<urn:gsma:imei:${imei.substring(0, 8)}-${imei.substring(8, 14)}-0>"
            val local =
                if(socket.gLocalAddr() is Inet6Address)
                    "[${socket.gLocalAddr().hostAddress}]:${serverSocket.localPort}"
                else
                    "${socket.gLocalAddr().hostAddress}:${serverSocket.localPort}"
            val transport = if (socket is SipConnectionTcp) "tcp" else "udp"
            val contactTel =
                """<sip:$myTel@$local;transport=$transport>;expires=7200;+sip.instance="$sipInstance";+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel";+g.3gpp.smsip;audio"""
            val outgoingInviteSessionTimer = inviteSessionTimerPolicy.currentForRealm(realm)
            val myHeaders = commonHeaders +
                """
                    From: <$mySip>
                    To: <$to>
                    P-Preferred-Identity: <$mySip>
                    P-Asserted-Identity: <$mySip>
                    Expires: 7200
                    Require: sec-agree
                    Proxy-Require: sec-agree
                    Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, MESSAGE, PRACK, OPTIONS
                    P-Early-Media: supported
                    Content-Type: application/sdp
                    Session-Expires: ${outgoingInviteSessionTimer.sessionExpiresSeconds}
                    Supported: 100rel, replaces, timer, precondition
                    Accept: application/sdp
                    Min-SE: ${outgoingInviteSessionTimer.minSeSeconds}
                    Accept-Contact: *;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel"
                    P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.mmtel
                    Contact: $contactTel
                    """.toSipHeadersMap() + generateCallId() - "p-asserted-identity"
            // P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.mmtel
            // Accept-Contact: *;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel"
            val singtelStockOutgoingTargetUri = if (isSingTelStockOutgoingCarrier()) {
                singtelPublicSipUri(normalizedPhoneNumber)
            } else {
                to
            }

            val singtelStockOutgoingHeaders = if (isSingTelStockOutgoingCarrier()) {
                val singtelStockIdentity = singtelPublicSipUri(myTel)
                val singtelStockFromTag = myHeaders["from"]?.firstOrNull()
                    ?.substringAfter(";tag=", missingDelimiterValue = "")
                    ?.substringBefore(";")
                    ?.takeIf { it.isNotBlank() }
                    ?: "phh${System.currentTimeMillis().toString(16)}"
                val singtelStockContact = "<sip:$imsi@$local;transport=$transport>;expires=7200;" +
                    "+sip.instance=\"$sipInstance\";audio;+g.3gpp.accesstype=\"cellular\";" +
                    "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\";+g.3gpp.smsip"
                val singtelCompactContact = "<sip:$imsi@$local;transport=$transport>"
                val singtelStockPaniValue = commonHeaders.entries
                    .firstOrNull { it.key.equals("p-access-network-info", ignoreCase = true) }
                    ?.value
                    ?.firstOrNull()
                    ?: "3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=5250102C6B611D01"

                val singtelStockBaseHeaders = myHeaders.filterKeys { key ->
                    key.equals("via", ignoreCase = true) ||
                        key.equals("max-forwards", ignoreCase = true) ||
                        key.equals("user-agent", ignoreCase = true) ||
                        key.equals("route", ignoreCase = true) ||
                        key.equals("call-id", ignoreCase = true) ||
                        key.equals("security-verify", ignoreCase = true) ||
                        key.equals("proxy-require", ignoreCase = true)
                }

                // Direct stock-like SingTel INVITE: whitelist only the dynamic dialog and
                // security headers, then add the originating MMTEL shape explicitly. Do not
                // carry the generic TEL-URI identity headers from main.
                /*
                 * Keep the originating SingTel header set intentionally small.
                 * Security-Verify and Content-Type are required/accepted, but
                 * optional identity/access/capability headers make the first
                 * protected INVITE large enough to be dropped by this IMS path.
                 */
                singtelStockBaseHeaders + """
                    From: <$singtelStockIdentity>;tag=$singtelStockFromTag
                    To: <$singtelStockOutgoingTargetUri>
                    Contact: $singtelCompactContact
                    P-Preferred-Identity: <$singtelStockIdentity>
                    Expires: 7200
                    Require: sec-agree
                    Proxy-Require: sec-agree
                    Content-Type: application/sdp
                    Allow: INVITE, ACK, CANCEL, BYE, OPTIONS
                    Supported: sec-agree
                    Request-Disposition: no-fork
                    P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.mmtel
                    CSeq: 1 INVITE
                """.toSipHeadersMap()
            } else {
                myHeaders
            }

            val msg =
                SipRequest(
                    SipMethod.INVITE,
                    singtelStockOutgoingTargetUri,
                    singtelStockOutgoingHeaders,
                    outgoingInviteBody
                )
            val outgoingInviteCallId = msg.headers["call-id"]!![0]
            val outgoingInviteCseq = msg.headers["cseq"]?.getOrNull(0)
                ?.substringBefore(" ")
                ?.toIntOrNull()
                ?: 1
            val outgoingDialogNextCseq = AtomicInteger(outgoingInviteCseq + 1)
            pendingOutgoingInvite = PendingOutgoingInvite(
                callId = outgoingInviteCallId,
                destination = singtelStockOutgoingTargetUri,
                headers = msg.headers,
                rtpSocket = rtpSocket,
                body = outgoingInviteBody,
            )
            val prackedReliableProvisionals = mutableSetOf<String>()
            setResponseCallback(outgoingInviteCallId) { r: SipResponse ->
                val responseCallId = r.headers["call-id"]?.getOrNull(0).orEmpty()
                val responseCseqForLog = r.headers["cseq"]?.getOrNull(0)
                val activeCallIdForResponse = currentCall?.callIdOrNull()
                val pendingCallIdForResponse = pendingOutgoingInvite?.callId
                if (responseCallId != outgoingInviteCallId ||
                    (activeCallIdForResponse != responseCallId && pendingCallIdForResponse != responseCallId)) {
                    Rlog.w(TAG, "Ignoring stale outgoing response: status=${r.statusCode} ${r.statusString} cseq=$responseCseqForLog callId=$responseCallId active=$activeCallIdForResponse pending=$pendingCallIdForResponse expected=$outgoingInviteCallId")
                    return@setResponseCallback true
                }

                var resp = r
                var cseq = resp.headers["cseq"]!![0]

                var rseqHandled = false
                // If we stopped our process to PRACK a response, start again processing it
                if (cseq.contains("PRACK")) {
                    val savedProvisional = respInFlight
                    if (savedProvisional == null) {
                        Rlog.w(TAG, "Ignoring PRACK response without pending provisional response: status=${resp.statusCode} ${resp.statusString} cseq=$cseq")
                        return@setResponseCallback false
                    }
                    if (resp.statusCode >= 300) {
                        Rlog.w(TAG, "PRACK failed for pending provisional response: status=${resp.statusCode} ${resp.statusString} cseq=$cseq")
                        val failedReliableKey = "${savedProvisional.headers["rseq"]?.getOrNull(0).orEmpty()} ${savedProvisional.headers["cseq"]?.getOrNull(0).orEmpty()}"
                        if (prackedReliableProvisionals.remove(failedReliableKey)) {
                            Rlog.w(TAG, "Removing failed PRACK key so retransmitted reliable provisional can be retried: $failedReliableKey")
                        }
                        respInFlight = null
                        return@setResponseCallback false
                    }
                    resp = savedProvisional
                    respInFlight = null
                    cseq = resp.headers["cseq"]!![0]
                    rseqHandled = true
                }

                if (cseq.contains("ACK")) return@setResponseCallback false
                if (cseq.contains("BYE")) {
                    val byeCallId = resp.callIdOrEmpty()
                    if (resp.statusCode in 200..299) {
                        Rlog.d(TAG, "Outgoing BYE accepted; clearing dialog callId=$byeCallId cseq=$cseq")
                    } else if (resp.statusCode >= 300) {
                        Rlog.w(
                            TAG,
                            "Outgoing BYE failed; clearing local dialog anyway: " +
                                "status=${resp.statusCode} ${resp.statusString} cseq=$cseq callId=$byeCallId",
                        )
                    } else {
                        return@setResponseCallback false
                    }
                    currentCall = null
                    clearPendingOutgoingInvite(
                        byeCallId,
                        closeRtpSocket = false,
                        reason = "outgoing BYE response $cseq ${resp.statusCode}",
                    )
                    return@setResponseCallback true
                }

                if (cseq.contains("INVITE") && (resp.statusCode == 200 || resp.statusCode == 202)) {
                    val finalInviteCallId = resp.callIdOrEmpty()
                    val finalInviteAfterLocalCancel = pendingOutgoingInvite?.callId == finalInviteCallId &&
                        pendingOutgoingInvite?.cancelSent?.get() == true
                                        val finalInviteHasSdp =
                        resp.headers["content-type"]?.getOrNull(0) == "application/sdp"

                    if (finalInviteAfterLocalCancel) {
                        Rlog.w(TAG, "Final INVITE answer arrived after local CANCEL; ACK first, then BYE once dialog state exists callId=$finalInviteCallId")
                    }
                    // ACK C-Seq must be the same as INVITE C-Seq
                    // Extract C-Seq
                    val cseqLine = resp.headers["cseq"]!![0]
                    val cseq = cseqLine.split(" ")[0].toInt()
                    val newTo = resp.headers["to"]!![0]
                    val newFrom = resp.headers["from"]!![0]
                    // ACK to 2xx must be sent to the Contact from the response (RFC 3261 §13.2.2.4)
                    val ackTo = resp.headers["contact"]?.get(0)
                        ?.let { extractDestinationFromContact(it) } ?: to
                    // ACK is a dialog request; route set comes from Record-Route in the 200 OK
                    // (RFC 3261 §12.1.2), not from the registration Service-Route in myHeaders.
                    val dialogRoute = resp.headers["record-route"]
                    val ackHeaders = if (dialogRoute != null) myHeaders + ("route" to dialogRoute) else myHeaders
                    val msg2 =
                        SipRequest(
                            SipMethod.ACK,
                            ackTo,
                            ackHeaders - "content-type" + """
                                CSeq: $cseq ACK
                                To: $newTo
                                From: $newFrom
                                """.toSipHeadersMap()
                        )
                    Rlog.d(TAG, "Sending $msg2")
                    synchronized(socket.gWriter()) { socket.gWriter().write(msg2.toByteArray()); socket.gWriter().flush() }
                    callStarted.set(true)
                    // Update dialog route set from the confirmed 200 OK (RFC 3261 §12.1.2)
                    // so that subsequent in-dialog requests (BYE, UPDATE) use the correct route.
                    val rrFrom200Ok = resp.headers["record-route"]
                    val remoteTargetFrom200Ok = resp.headers["contact"]?.getOrNull(0)
                        ?.let { extractDestinationFromContact(it) }
                    currentCall = currentCall?.let { confirmedCall ->
                        var confirmedHeaders = confirmedCall.callHeaders
                        if (rrFrom200Ok != null) {
                            confirmedHeaders = confirmedHeaders + ("record-route" to rrFrom200Ok) + ("route" to rrFrom200Ok)
                        }
                        // INVITE uses its original CSeq for ACK. Keep later in-dialog requests
                        // past any PRACK/UPDATE/BYE CSeq already allocated while the call was pending.
                        val nextDialogCseq = maxOf(cseq + 1, outgoingDialogNextCseq.get())
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
                            "Outgoing confirmed dialog after ACK: " +
                                "remoteTarget=${confirmedCall.remoteContact} " +
                                "nextLocalCseq=${confirmedCall.localCseq.get()} " +
                                "route=$routeHeader",
                        )
                    }
                    if (finalInviteAfterLocalCancel) {
                        Rlog.w(TAG, "Confirmed outgoing dialog after local CANCEL without final SDP; sending BYE immediately callId=$finalInviteCallId")
                        currentCall?.let { sendByeForCall(it) }
                        currentCall = null
                        clearPendingOutgoingInvite(finalInviteCallId, closeRtpSocket = true, reason = "final answer without SDP after local CANCEL")
                        return@setResponseCallback true
                    } else if (!finalInviteHasSdp) {
                        clearPendingOutgoingInvite(finalInviteCallId, closeRtpSocket = false, reason = "final INVITE answer without SDP")
                        val confirmedOutgoingCall = currentCall
                    if (confirmedOutgoingCall != null) {
                        confirmedOutgoingCall.outgoingRtpReceived.set(false)
                        Rlog.d(TAG, "Final outgoing answer received; clearing early-media RTP gate until post-answer RTP arrives callId=$finalInviteCallId")
                        scheduleOutgoingPostAnswerRtpTimeout(finalInviteCallId)
                        maybeNotifyOutgoingCallConnected(confirmedOutgoingCall, "final INVITE answer")
                    } else {
                        Rlog.w(TAG, "Final INVITE answer but currentCall is null after ACK callId=$finalInviteCallId")
                    }
                    }
                } else {
                    Rlog.d(TAG, "Invite got status ${resp.statusCode} = ${resp.statusString}")
                    if (resp.statusCode in 180..199) {
                        val progressCseq = resp.headers["cseq"]?.getOrNull(0).orEmpty()
                        val progressHasSdp = resp.headers["content-type"]?.getOrNull(0)
                            ?.equals("application/sdp", ignoreCase = true) == true

                        if (progressCseq.contains("INVITE", ignoreCase = true) && !progressHasSdp) {
                            Rlog.d(
                                TAG,
                                "Outgoing call progressing without SDP: " +
                                    "status=${resp.statusCode} ${resp.statusString} cseq=$progressCseq",
                            )
                            onOutgoingCallProgressing?.invoke(
                                Object(),
                                mapOf(
                                    "call-id" to resp.callIdOrEmpty(),
                                    "statusCode" to resp.statusCode.toString(),
                                    "statusString" to resp.statusString,
                                    "cseq" to progressCseq,
                                    "local-ringback" to "true",
                                ),
                            )
                        }
                    }

                    if(resp.statusCode >= 400) {
                        val failedPendingInvite = pendingOutgoingInvite
                        if (failedPendingInvite != null &&
                            failedPendingInvite.callId == resp.callIdOrEmpty() &&
                            retryOutgoingInviteAfter422(failedPendingInvite, resp, outgoingDialogNextCseq)
                        ) {
                            return@setResponseCallback false
                        }
                        val failedCallId = resp.callIdOrEmpty()
                        val failedCseq = resp.headers["cseq"]?.getOrNull(0).orEmpty()
                        val activeCallId = currentCall?.callIdOrNull()
                        val pendingCallId = pendingOutgoingInvite?.callId

                        if (activeCallId != failedCallId && pendingCallId != failedCallId) {
                            Rlog.w(TAG, "Ignoring stale outgoing dialog failure: status=${resp.statusCode} ${resp.statusString} cseq=$failedCseq callId=$failedCallId active=$activeCallId pending=$pendingCallId")
                            return@setResponseCallback true
                        }

                        if (failedPendingInvite != null &&
                            failedPendingInvite.callId == failedCallId &&
                            failedCseq.contains("INVITE", ignoreCase = true) &&
                            retryOutgoingInviteAfterIllegalSdp(failedPendingInvite, resp, outgoingDialogNextCseq)
                        ) {
                            return@setResponseCallback false
                        }

                        Rlog.w(TAG, "Outgoing dialog request failed: status=${resp.statusCode} ${resp.statusString} cseq=$failedCseq callId=$failedCallId")
                        stopCallRuntime("outgoing dialog failure")

                        val failedPending = pendingOutgoingInvite
                        if (failedPending != null && failedPending.callId == failedCallId &&
                            !failedCseq.contains("INVITE") && !failedPending.cancelSent.get()) {
                            Rlog.w(TAG, "Early outgoing in-dialog request failed; cancelling pending INVITE callId=$failedCallId")
                            sendCancelForPendingOutgoingInvite(failedPending, "early dialog request failed: $failedCseq ${resp.statusCode}")
                        }

                        if (activeCallId == failedCallId) {
                            currentCall = null
                        }
                        clearPendingOutgoingInvite(failedCallId, closeRtpSocket = activeCallId != failedCallId, reason = "outgoing dialog failure $failedCseq ${resp.statusCode}")
                        onCancelledCall?.invoke(Object(), "",
                            mapOf(
                                "statusCode" to resp.statusCode.toString(),
                                "statusString" to resp.statusString,
                                "cseq" to failedCseq))
                        // The whole call failed, so drop that call-id
                        return@setResponseCallback true
                    }
                }

                if(resp.headers["rseq"]?.isNotEmpty() == true && !rseqHandled) {
                    val reliableKey = "${resp.headers["rseq"]?.getOrNull(0).orEmpty()} ${resp.headers["cseq"]?.getOrNull(0).orEmpty()}"
                    if (!prackedReliableProvisionals.add(reliableKey)) {
                        Rlog.w(TAG, "Ignoring duplicate reliable provisional response already PRACKed: $reliableKey")
                        return@setResponseCallback false
                    }
                    val currentCallNextCseq = currentCall?.localCseq?.get() ?: 0
                    val allocatorNextCseq = outgoingDialogNextCseq.get()
                    if (currentCallNextCseq > allocatorNextCseq) {
                        Rlog.d(
                            TAG,
                            "Syncing outgoing PRACK CSeq allocator from current call: " +
                                "allocator=$allocatorNextCseq currentCallNext=$currentCallNextCseq key=$reliableKey",
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
                    prack(resp, prackCseq)
                    Rlog.d(
                        TAG,
                        "Outgoing PRACK consumed local CSeq=$prackCseq " +
                            "nextAllocatorCseq=${outgoingDialogNextCseq.get()} " +
                            "currentCallNextCseq=${currentCall?.localCseq?.get()} key=$reliableKey",
                    )
                    respInFlight = resp
                    return@setResponseCallback false
                }

                val isSdp = resp.headers["content-type"]?.get(0) == "application/sdp"
                val isPrecondition = resp.headers["require"]?.find { it.contains("precondition") } != null

                if (!isSdp) return@setResponseCallback false

                val respSdp = resp.body.toString(Charsets.UTF_8).split("[\r\n]+".toRegex()).toList()
                SipAudioCodecSdpLogger.logRemoteAudioCodecCandidates(
                    tag = TAG,
                    context = "outgoing SDP response ${resp.statusCode} callId=${resp.callIdOrEmpty()}",
                    sdp = respSdp,
                )

                fun sdpElement(command: String): String? {
                    val v = respSdp.firstOrNull { it.startsWith("$command=")} ?: return null
                    return v.substring(2)
                }

                val respAttributes = respSdp
                    .filter { it.startsWith("a=") }
                    .map { it.substring(2) }
                fun responseTrackRequirements(track: Int): String? =
                    respAttributes.firstOrNull { it.startsWith("fmtp:$track") }

                fun lookResponseTrackMatching(codec: String, notAdditional: String = ""): Pair<Int, String>? {
                    val offeredPayloads = sdpElement("m")
                        ?.trim()
                        ?.split("\\s+".toRegex())
                        ?.drop(3)
                        ?.mapNotNull { it.toIntOrNull() }
                        ?.toSet()
                        .orEmpty()
                    val maps = respAttributes.filter { it.startsWith("rtpmap:") && it.contains(codec) }
                    val matches = maps.mapNotNull { m ->
                        val track = m.split("[: ]+".toRegex()).getOrNull(1)?.toIntOrNull()
                        if (track != null && offeredPayloads.contains(track)) Pair(track, m) else null
                    }
                    val sorted = matches.sortedBy { m ->
                        val fmtp = responseTrackRequirements(m.first).orEmpty()
                        when {
                            fmtp.contains("octet-align=1", ignoreCase = true) &&
                                notAdditional.isNotEmpty() &&
                                fmtp.contains(notAdditional, ignoreCase = true) -> 100
                            fmtp.contains("octet-align=1", ignoreCase = true) -> 100
                            else -> 0
                        }
                    }
                    Rlog.d(TAG, "Outgoing answer matching $codec offered=$offeredPayloads got=$sorted")
                    return sorted.firstOrNull()
                }

                val selectedAudioCodec = SipAudioCodecNegotiator.selectOutgoingSpeechCodecFromAnswer(
                    logTag = TAG,
                    sdp = respSdp,
                    context = "outgoing SDP response ${resp.statusCode} callId=${resp.callIdOrEmpty()}",
                    amrWbMediaCodecAvailable = amrWbMediaCodecAvailable,
                )
                val selectedAmr = lookResponseTrackMatching(
                    SipAudioCodecNegotiator.speechCodecRtpmapName(selectedAudioCodec),
                    notAdditional = "octet-align=1",
                )
                if (selectedAmr == null) {
                    Rlog.w(
                        TAG,
                        "Outgoing SDP response lacks compatible ${SipAudioCodecNegotiator.speechCodecRtpmapName(selectedAudioCodec)}; " +
                            "falling back to AMR-NB/8000 tracks",
                    )
                }
                val selectedDtmf = lookResponseTrackMatching(
                    SipAudioCodecNegotiator.telephoneEventRtpmapName(selectedAudioCodec),
                )
                if (selectedDtmf == null) {
                    Rlog.w(
                        TAG,
                        "Outgoing SDP response lacks compatible ${SipAudioCodecNegotiator.telephoneEventRtpmapName(selectedAudioCodec)}; " +
                            "falling back to telephone-event/8000",
                    )
                }
                val dialogAudioCodec =
                    if (selectedAmr != null && selectedDtmf != null) selectedAudioCodec else SipAudioCodecs.AMR_NB
                val (dialogAmrTrack, dialogAmrTrackDesc) =
                    selectedAmr?.takeIf { selectedDtmf != null } ?: (amrNbTrack to "rtpmap:$amrNbTrack AMR/8000/1")
                val (dialogDtmfTrack, dialogDtmfTrackDesc) =
                    selectedDtmf?.takeIf { selectedAmr != null } ?: (dtmfNbTrack to "rtpmap:$dtmfNbTrack telephone-event/8000")

                val rtpRemotePort = sdpElement("m")!!.split(" ")[1]
                val rtpRemoteAddr = InetAddress.getByName(sdpElement("c")!!.split(" ")[2])
                val rtpRemotePortInt = rtpRemotePort.toInt()
                try {
                    if (!rtpSocket.isConnected || rtpSocket.inetAddress != rtpRemoteAddr || rtpSocket.port != rtpRemotePortInt) {
                        rtpSocket.connect(rtpRemoteAddr, rtpRemotePortInt)
                        Rlog.d(TAG, "Outgoing RTP socket connected to ${rtpRemoteAddr}:${rtpRemotePortInt} local=${rtpSocket.localAddress}:${rtpSocket.localPort}")
                    }
                } catch (e: Exception) {
                    Rlog.w(TAG, "Failed to connect outgoing RTP socket to ${rtpRemoteAddr}:${rtpRemotePortInt}", e)
                }
                val inviteCseqForDialog = resp.headers["cseq"]!![0].substringBefore(" ").toIntOrNull() ?: 1
                val nextLocalCseqForDialog = maxOf(
                    inviteCseqForDialog + 1,
                    outgoingDialogNextCseq.get(),
                    currentCall?.localCseq?.get() ?: 0,
                )
                // PhhIms: final INVITE SDP media restart.
                // Keep the media state selected by a provisional 183 before replacing
                // currentCall with the later/final SDP answer. Some IMS cores answer
                // 183 with AMR-NB and then switch the confirmed 200 OK to AMR-WB.
                val previousOutgoingDialogCall = currentCall
                currentCall = Call(
                    outgoing = true,
                    audioCodec = dialogAudioCodec,
                    amrTrack = dialogAmrTrack,
                    amrTrackDesc = dialogAmrTrackDesc,
                    dtmfTrack = dialogDtmfTrack,
                    dtmfTrackDesc = dialogDtmfTrackDesc,
                    // Update from/to/call-id based on the response we got to include the remote tag.
                    // Keep the response Record-Route too; later local BYE/UPDATE must use it as Route.
                    callHeaders = myHeaders - "require" - "content-type" +
                        ("from" to resp.headers["from"]!!) +
                        ("to" to resp.headers["to"]!!) +
                        ("call-id" to resp.headers["call-id"]!!) +
                        (resp.headers["record-route"]?.let { mapOf("record-route" to it, "route" to it) } ?: emptyMap()),
                    rtpRemoteAddr = rtpRemoteAddr,
                    rtpRemotePort = rtpRemotePortInt,
                    rtpSocket = rtpSocket,
                    sdp = resp.body,
                    hasEarlyMedia = resp.headers["p-early-media"]?.isNotEmpty() == true,
                    remoteContact = extractDestinationFromContact(resp.headers["contact"]!![0]),
                    localCseq = AtomicInteger(nextLocalCseqForDialog),
                )
                restartOutgoingMediaAfterDialogSdpCodecChange(
                    previousOutgoingDialogCall,
                    currentCall,
                    "status=${resp.statusCode} cseq=${resp.headers["cseq"]?.getOrNull(0).orEmpty()}",
                )
                val responseCseq = resp.headers["cseq"]?.getOrNull(0).orEmpty()
                val outgoingDialogPhase = when {
                    responseCseq.contains("UPDATE") -> "update"
                    responseCseq.contains("INVITE") && (resp.statusCode == 200 || resp.statusCode == 202) -> "final-answer"
                    resp.statusCode in 180..199 -> "early"
                    else -> "sdp"
                }
                Rlog.d(
                    TAG,
                    "Outgoing $outgoingDialogPhase dialog SDP: status=${resp.statusCode} cseq=$responseCseq " +
                        "codec=${currentCall?.audioCodec?.name}/${currentCall?.audioCodec?.sampleRate} " +
                        "amrTrack=${currentCall?.amrTrack} dtmfTrack=${currentCall?.dtmfTrack} " +
                        "remoteTarget=${currentCall?.remoteContact} nextLocalCseq=${currentCall?.localCseq?.get()} " +
                        "route=${currentCall?.callHeaders?.get("route")}",
                )

                val outgoingMediaFormatChanged =
                    threadsStarted.get() &&
                        previousOutgoingDialogCall != null &&
                        responseCseq.contains("INVITE") &&
                        (resp.statusCode == 200 || resp.statusCode == 202) &&
                        (
                            previousOutgoingDialogCall.audioCodec != dialogAudioCodec ||
                                previousOutgoingDialogCall.amrTrack != dialogAmrTrack ||
                                previousOutgoingDialogCall.dtmfTrack != dialogDtmfTrack ||
                                previousOutgoingDialogCall.rtpRemoteAddr != rtpRemoteAddr ||
                                previousOutgoingDialogCall.rtpRemotePort != rtpRemotePortInt
                        )
                if (outgoingMediaFormatChanged) {
                    Rlog.w(
                        TAG,
                        "Outgoing final INVITE SDP changed running media format: " +
                            "oldCodec=${previousOutgoingDialogCall?.audioCodec?.name}/${previousOutgoingDialogCall?.audioCodec?.sampleRate} " +
                            "oldAmr=${previousOutgoingDialogCall?.amrTrack} oldDtmf=${previousOutgoingDialogCall?.dtmfTrack} " +
                            "oldRtp=${previousOutgoingDialogCall?.rtpRemoteAddr}:${previousOutgoingDialogCall?.rtpRemotePort} " +
                            "newCodec=${dialogAudioCodec.name}/${dialogAudioCodec.sampleRate} " +
                            "newAmr=$dialogAmrTrack newDtmf=$dialogDtmfTrack " +
                            "newRtp=$rtpRemoteAddr:$rtpRemotePortInt",
                    )
                }
                if (responseCseq.contains("INVITE") && (resp.statusCode == 200 || resp.statusCode == 202)) {
                    val finalInviteCallId = resp.callIdOrEmpty()
                    val finalInviteAfterLocalCancel = pendingOutgoingInvite?.callId == finalInviteCallId &&
                        pendingOutgoingInvite?.cancelSent?.get() == true
                    if (finalInviteAfterLocalCancel) {
                        Rlog.w(TAG, "Confirmed outgoing dialog after local CANCEL; sending BYE immediately callId=$finalInviteCallId")
                        currentCall?.let { sendByeForCall(it) }
                        currentCall = null
                        clearPendingOutgoingInvite(finalInviteCallId, closeRtpSocket = true, reason = "final answer after local CANCEL")
                        return@setResponseCallback true
                    }

                    clearPendingOutgoingInvite(finalInviteCallId, closeRtpSocket = false, reason = "final INVITE answer")
                    if (threadsStarted.compareAndSet(false, true)) {
                        Rlog.d(TAG, "Starting outgoing media threads from final INVITE SDP")
                        callDecodeThread()
                        callEncodeThread()
                    } else if (outgoingMediaFormatChanged) {
                        val mediaRestartGeneration = callGeneration.incrementAndGet()
                        Rlog.w(
                            TAG,
                            "Restarting outgoing media threads after final INVITE SDP media change: " +
                                "generation=$mediaRestartGeneration " +
                                "codec=${dialogAudioCodec.name}/${dialogAudioCodec.sampleRate} " +
                                "amrTrack=$dialogAmrTrack dtmfTrack=$dialogDtmfTrack",
                        )
                        callDecodeThread()
                        callEncodeThread()
                    } else {
                        Rlog.d(TAG, "Outgoing media threads already started before final INVITE SDP")
                    }
                    return@setResponseCallback false
                }

                // This isn't the answer to our INVITE, but to our later precondition UPDATE
                // TODO Actually check cseq
                if(resp.headers["cseq"]?.get(0)?.contains("UPDATE") == true) {
                    if(isSdp && resp.statusCode == 200) {
                        // Nothing to do here, we've already upgraded the call with the new SDP, everything's fine
                        return@setResponseCallback false
                    }
                }

                if(isPrecondition && resp.statusCode == 183) {
                    Rlog.d(TAG, "Handling precondition...")
                    val currLocal = respSdp.first { it.startsWith("a=curr:qos local")}
                    // No resource has been allocated at either side
                    val localNone = currLocal.contains("none")
                    Rlog.d(TAG, "precondition: Curr is $currLocal $localNone")
                    val currRemote = respSdp.first { it.startsWith("a=curr:qos remote")}
                    val remoteNone = currRemote.contains("none")
                    val remoteHasLocalQos = currLocal.contains("sendrecv")
                    val needsLocalQosUpdate = localNone || remoteNone
                    Rlog.d(TAG, "precondition: Remote is $currRemote remoteNone=$remoteNone remoteHasLocalQos=$remoteHasLocalQos needsLocalQosUpdate=$needsLocalQosUpdate")
                    if (needsLocalQosUpdate) {
                        // "Allocating our local resource" and update the call
                        if (threadsStarted.compareAndSet(false, true)) {
                            Rlog.d(TAG, "Starting outgoing media threads from precondition 183 SDP")
                            callDecodeThread()
                            callEncodeThread()
                        }

                        val remoteMaxptimeLine = respSdp.firstOrNull { it.startsWith("a=maxptime:") } ?: "a=maxptime:40"

                        val localUpdateSdpLines = sdp.toString(Charsets.UTF_8)
                            .split("[\r\n]+".toRegex())
                            .filter { it.isNotBlank() }
                            .map { line ->
                                when {
                                    line.startsWith("o=") -> {
                                        val v = currentCall?.localSdpVersion?.incrementAndGet() ?: 3
                                        line.replace(Regex("^(o=\\S+\\s+\\S+\\s+)\\S+(\\s+IN\\s+IP[46]\\s+.*)$"), "$1$v$2")
                                    }
                                    line.startsWith("a=maxptime:") -> remoteMaxptimeLine
                                    line.startsWith("a=curr:qos local") -> "a=curr:qos local sendrecv"
                                    line.startsWith("a=curr:qos remote") -> if (remoteHasLocalQos) "a=curr:qos remote sendrecv" else "a=curr:qos remote none"
                                    else -> line
                                }
                            }
                            .let { lines ->
                                if (lines.any { it.startsWith("a=conf:qos remote") }) {
                                    lines
                                } else {
                                    lines + "a=conf:qos remote sendrecv"
                                }
                            }

                        val newSdp = localUpdateSdpLines.joinToString("\r\n").toByteArray(Charsets.US_ASCII)

                        val updateHeaders = localDialogHeadersForRequest(currentCall!!, SipMethod.UPDATE) -
                            "content-length" +
                            ("content-type" to listOf("application/sdp"))

                        val msg2 =
                            SipRequest(
                                SipMethod.UPDATE,
                                currentCall!!.remoteContact ?: to,
                                updateHeaders,
                                newSdp
                            )
                        Rlog.d(TAG, "Sending $msg2")
                        writeSipBytesWithFlush(socket.gWriter(), "SipHandler msg2", msg2.toByteArray())
                    }

                    return@setResponseCallback false
                }

                if(!isPrecondition && resp.statusCode == 183) {
                    if (threadsStarted.compareAndSet(false, true)) {
                        Rlog.d(TAG, "Starting outgoing media threads from non-precondition 183 SDP")
                        callDecodeThread()
                        callEncodeThread()
                    }
                }

                false // Return true when we want to stop receiving messages for that call
            }
            Rlog.w(
                TAG,
                "Outgoing INVITE send context " +
                    imsDualSimDebugContext(
                        "callId=$outgoingInviteCallId cseq=${msg.headers["cseq"]?.getOrNull(0)} " +
                            "to=$singtelStockOutgoingTargetUri raw=$phoneNumber normalized=$normalizedPhoneNumber " +
                            "rtp=${rtpSocket.localAddress}:${rtpSocket.localPort} sdpBytes=${outgoingInviteBody.size}"
                    ),
            )
            Rlog.d(TAG, "Sending $msg")
            writeSipBytesWithFlush(socket.gWriter(), "SipHandler msg", msg.toByteArray())
        }
    }

    fun callDecodeThread() {
        val audioCodec = currentCall?.audioCodec ?: SipAudioCodecs.AMR_NB
        val gen = callGeneration.get()
        // Receiving thread
        thread {
            Rlog.d(TAG, "Decode thread started: codec=${audioCodec.name}/${audioCodec.sampleRate} gen=$gen")
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
                generation = gen,
            )

            val decoder = SipAudioCodecFactory.createStartedDecoder(
                audioCodec = audioCodec,
            )

            var receivedCount = 0
            while(true) {
                if (callStopped.get() || callGeneration.get() != gen) break
                val dgramBuf = ByteArray(2048)
                val dgram = DatagramPacket(dgramBuf, dgramBuf.size)
                val receiveCall = currentCall ?: break
                try {
                receiveCall.rtpSocket.receive(dgram)
            } catch (e: SocketTimeoutException) {
                // Expected idle receive window. Keep looping, but do not
                // hold the DatagramSocket monitor for seconds or spam logs.
                if (callStopped.get() || callGeneration.get() != gen || receiveCall.rtpSocket.isClosed) {
                    break
                }
                continue
            } catch (e: SocketException) {
                if (callStopped.get() || callGeneration.get() != gen || receiveCall.rtpSocket.isClosed) {
                    Rlog.d(TAG, "RTP receive socket closed; exiting decode thread: outgoing=${receiveCall.outgoing} local=${receiveCall.rtpSocket.localAddress}:${receiveCall.rtpSocket.localPort} callStopped=${callStopped.get()} genMismatch=${callGeneration.get() != gen} closed=${receiveCall.rtpSocket.isClosed}")
                } else {
                    Rlog.w(TAG, "RTP receive socket exception; exiting decode thread: outgoing=${receiveCall.outgoing} local=${receiveCall.rtpSocket.localAddress}:${receiveCall.rtpSocket.localPort} connected=${receiveCall.rtpSocket.isConnected} remote=${receiveCall.rtpRemoteAddr}:${receiveCall.rtpRemotePort}", e)
                }
                break
            } catch (t: Throwable) {
                Rlog.w(TAG, "Unexpected RTP receive failure; exiting decode thread", t)
                break
            }
            if (callStopped.get() || callGeneration.get() != gen) break
            receivedCount++
            if (receiveCall.outgoing) {
                if (callStarted.get()) {
                    receiveCall.outgoingRtpReceived.set(true)
                    maybeNotifyOutgoingCallConnected(receiveCall, "first post-answer remote RTP")
                } else {
                    val earlyCallId = receiveCall.callHeaders["call-id"]?.getOrNull(0).orEmpty()
                    if (receivedCount == 1) {
                        Rlog.d(TAG, "Outgoing early-media RTP before final answer; not marking connected callId=$earlyCallId")
                    }
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

                if (amrFrame == null) continue

                SipDownlinkAudioDecoder.queueCodecFrameAndDrainPcm(
                    logTag = TAG,
                    decoder = decoder,
                    codecFrame = amrFrame.codecFrame,
                    pcmQueue = downlinkPlayoutBuffers.pcmQueue,
                )
            }
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


    private fun handleInDialogInvite(request: SipRequest, call: Call, responseWriter: OutputStream): Int {
        val callId = request.callIdOrEmpty()
        val cseq = request.headers["cseq"]?.getOrNull(0).orEmpty()
        val sdp = request.body.toString(Charsets.UTF_8).split("[\r\n]+".toRegex()).toList()
        Rlog.d(TAG, "Handling in-dialog INVITE: callId=$callId cseq=$cseq sdp=$sdp")

        fun sdpElement(command: String): String? {
            val v = sdp.firstOrNull { it.startsWith("$command=") } ?: return null
            return v.substring(2)
        }

        val sdpConnectionData = sdpElement("c") ?: return 488
        val sdpMedia = sdpElement("m") ?: return 488
        val rtpRemote = sdpConnectionData.split(" ").getOrNull(2) ?: return 488
        val rtpRemoteAddr = InetAddress.getByName(rtpRemote)
        val rtpRemotePort = sdpMedia.split(" ").getOrNull(1)?.toIntOrNull() ?: return 488
        val attributes = sdp.filter { it.startsWith("a=") }.map { it.substring(2) }
        SipAudioCodecSdpLogger.logRemoteAudioCodecCandidates(
            tag = TAG,
            context = "remote SDP ${request.method} callId=${request.callIdOrEmpty()}",
            sdp = sdp,
        )

        fun lookTrackMatching(codec: String, notAdditional: String = ""): Pair<Int, String>? {
            val maps = attributes.filter { it.startsWith("rtpmap") && it.contains(codec) }
            val matches = maps.map { m ->
                val track = m.split("[: ]+".toRegex())[1].toInt()
                Pair(track, m)
            }
            val sorted = if (matches.size > 1) {
                matches.sortedBy { m ->
                    val fmtp = attributes.firstOrNull { it.startsWith("fmtp:${m.first}") }.orEmpty()
                    when {
                        codec.startsWith("AMR") && fmtp.isEmpty() -> 100
                        notAdditional.isNotEmpty() && fmtp.contains(notAdditional) -> 90
                        else -> 10
                    }
                }
            } else {
                matches
            }
            Rlog.d(TAG, "In-dialog INVITE matching $codec, got $sorted")
            return sorted.firstOrNull()
        }

        fun trackRequirements(track: Int): String? {
            return attributes.firstOrNull { it.startsWith("fmtp:$track") }
        }

        val selectedAudioCodec = call.audioCodec
        val (amrTrack, amrTrackDesc) =
            lookTrackMatching(SipAudioCodecNegotiator.speechCodecRtpmapName(selectedAudioCodec), notAdditional = "octet-align=1") ?: return 488
        val (dtmfTrack, dtmfTrackDesc) =
            lookTrackMatching(SipAudioCodecNegotiator.telephoneEventRtpmapName(selectedAudioCodec)) ?: return 488
        val amrFmtpAnswer =
            trackRequirements(amrTrack) ?: SipAudioCodecNegotiator.defaultSpeechFmtpAnswer(amrTrack, selectedAudioCodec)
        val remotePtime = attributes.firstOrNull { it.startsWith("ptime:") } ?: "ptime:20"
        val remoteMaxptime = attributes.firstOrNull { it.startsWith("maxptime:") } ?: "maxptime:20"
        val allTracks = listOf(amrTrack, dtmfTrack)
        val sdpBandwidthAs = SipAudioCodecNegotiator.sdpBandwidthAsKbps(selectedAudioCodec)
        val remoteBandwidthLines = sdp
            .filter { it.startsWith("b=", ignoreCase = true) }
            .map { it.substring(2).trim() }
            .filter { it.startsWith("AS:", ignoreCase = true) }
        val answerBandwidthLines = if (remoteBandwidthLines.isNotEmpty()) {
            remoteBandwidthLines
        } else {
            listOf("AS:$sdpBandwidthAs")
        }
        val remoteDirection = attributes.firstOrNull {
            it == "sendrecv" || it == "sendonly" || it == "recvonly" || it == "inactive"
        }
        val answerDirection = when (remoteDirection) {
            "sendonly" -> "recvonly"
            "recvonly" -> "sendonly"
            "inactive" -> "inactive"
            "sendrecv" -> "sendrecv"
            else -> null
        }
        Rlog.d(
            TAG,
            "Conservative in-dialog INVITE SDP answer: " +
                "bandwidth=$answerBandwidthLines ptime=$remotePtime maxptime=$remoteMaxptime " +
                "remoteDirection=$remoteDirection answerDirection=$answerDirection"
        )
        val localSdpSessionVersion = call.localSdpVersion.incrementAndGet().coerceAtLeast(3)
        Rlog.d(
            TAG,
            "In-dialog INVITE local SDP origin: owner=- sessionId=1 " +
                "sessionVersion=$localSdpSessionVersion"
        )
        val ipType = if (socket.gLocalAddr() is Inet6Address) "IP6" else "IP4"
        val answerSdpLines = mutableListOf(
            "v=0",
            "o=- 1 $localSdpSessionVersion IN $ipType ${socket.gLocalAddr().hostAddress}",
            "s=-",
            "c=IN $ipType ${socket.gLocalAddr().hostAddress}",
            "t=0 0",
            "m=audio ${call.rtpSocket.localPort} RTP/AVP ${allTracks.joinToString(" ")}",
        )
        answerBandwidthLines.forEach { answerSdpLines += "b=$it" }
        answerSdpLines += listOf(
            "a=$amrTrackDesc",
            "a=$remotePtime",
            "a=$remoteMaxptime",
            "a=$dtmfTrackDesc",
            "a=$amrFmtpAnswer",
            "a=fmtp:$dtmfTrack 0-15",
        )
        answerDirection?.let { answerSdpLines += "a=$it" }
        val answerSdp = answerSdpLines.joinToString("\r\n").toByteArray(Charsets.US_ASCII)

        currentCall = call.copy(
            amrTrack = amrTrack,
            amrTrackDesc = amrTrackDesc,
            dtmfTrack = dtmfTrack,
            dtmfTrackDesc = dtmfTrackDesc,
            sdp = answerSdp,
            rtpRemoteAddr = rtpRemoteAddr,
            rtpRemotePort = rtpRemotePort,
            remoteContact = request.headers["contact"]?.getOrNull(0)
                ?.let { extractDestinationFromContact(it) }
                ?: call.remoteContact,
        )

        val requestSessionExpires = request.headers["session-expires"]?.getOrNull(0)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val requestMinSe = request.headers["min-se"]?.getOrNull(0)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val inDialogSessionTimerHeaders = mutableMapOf<String, List<String>>()
        requestSessionExpires?.let { inDialogSessionTimerHeaders["session-expires"] = listOf(it) }
        requestMinSe?.let { inDialogSessionTimerHeaders["min-se"] = listOf(it) }
        Rlog.d(
            TAG,
            "In-dialog INVITE session timer response headers: " +
                "Session-Expires=$requestSessionExpires Min-SE=$requestMinSe"
        )

        val responseHeaders = responseHeadersFromRequest(
            request,
            extra = """
                Contact: ${call.callHeaders["contact"]!!.first()}
                Supported: timer
                Content-Type: application/sdp
            """.toSipHeadersMap() + inDialogSessionTimerHeaders
        )
        val response = SipResponse(
            statusCode = 200,
            statusString = "OK",
            headersParam = responseHeaders,
            body = answerSdp,
        )
        Rlog.d(TAG, "Replying to in-dialog INVITE without creating a new incoming call: $response")
        synchronized(responseWriter) { responseWriter.write(response.toByteArray()) }
        return 0
    }

    fun handleCall(request: SipRequest): Int {
        val contentType = request.headers["content-type"]?.get(0)
        if (contentType != "application/sdp") return 404
        val incomingCallId = request.headers["call-id"]!![0]
        if (wasRecentlyTerminatedIncomingCall(incomingCallId)) {
            val incomingCseq = request.headers["cseq"]?.getOrNull(0).orEmpty()
            val maybeCurrentCall = currentCall
            val isAcceptedPreAckCurrentCall =
                maybeCurrentCall != null &&
                    !maybeCurrentCall.outgoing &&
                    maybeCurrentCall.callIdOrEmpty() == incomingCallId &&
                    (incomingAcceptedAwaitingAck.get() || incomingFinalResponseSent.get()) &&
                    incomingHangupAfterAck.get()

            if (!isAcceptedPreAckCurrentCall) {
                Rlog.w(TAG, "Rejecting duplicate incoming INVITE for recently terminated Call-ID: callId=$incomingCallId cseq=$incomingCseq")
                return 486
            }

            Rlog.w(
                TAG,
                "Allowing duplicate incoming INVITE for accepted pre-ACK call despite recently terminated marker: " +
                    "callId=$incomingCallId cseq=$incomingCseq awaitingAck=${incomingAcceptedAwaitingAck.get()}",
            )
        }
        val incomingResponseWriter = dispatcher.writerForCallId(incomingCallId) ?: socket.gWriter()
        val existingCall = currentCall
        val isInDialogInvite = existingCall != null &&
            existingCall.callHeaders["call-id"]?.getOrNull(0) == incomingCallId &&
            request.headers["from"]?.any { it.contains(";tag=", ignoreCase = true) } == true &&
            request.headers["to"]?.any { it.contains(";tag=", ignoreCase = true) } == true
        if (isInDialogInvite) {
            return handleInDialogInvite(request, existingCall!!, incomingResponseWriter)
        }
        if (existingCall != null && !existingCall.outgoing && existingCall.callIdOrEmpty() == incomingCallId) {
            val incomingCseq = request.headers["cseq"]?.getOrNull(0).orEmpty()
            val duplicateAnswered = incomingFinalResponseSent.get() || incomingAcceptedAwaitingAck.get() || callStarted.get()
            val refreshedHeaders = responseHeadersFromRequest(
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
                "Refreshing duplicate incoming INVITE for existing incoming dialog: " +
                    "callId=$incomingCallId cseq=$incomingCseq " +
                    "finalResponseSent=${incomingFinalResponseSent.get()} " +
                    "awaitingAck=${incomingAcceptedAwaitingAck.get()} callStarted=${callStarted.get()}",
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

                val duplicateFinalSdpHeaders = if (!duplicateOmitFinalSdp) {
                    (
                        "Content-Type: application/sdp\n" +
                            "Content-Length: ${duplicateFinalBody.size}\n"
                    ).toSipHeadersMap()
                } else {
                    "Content-Length: 0".toSipHeadersMap()
                }
                val duplicateFinalHeaders =
                    duplicateFinalCall.callHeaders -
                        "rseq" -
                        "security-verify" -
                        "p-access-network-info" -
                        "content-type" -
                        "content-length" +
                        (
                            "Session-Expires: 1800;refresher=uas\n" +
                                "Contact: ${duplicateFinalCall.callHeaders["contact"]!!.first()}\n"
                        ).toSipHeadersMap() +
                        duplicateFinalSdpHeaders

                val duplicateFinalResponse = SipResponse(
                    statusCode = 200,
                    statusString = "OK",
                    headersParam = duplicateFinalHeaders,
                    body = duplicateFinalBody,
                    autofill = false,
                )
                val duplicateFinalBytes = duplicateFinalResponse.toByteArray()
                Rlog.w(
                    TAG,
                    "Re-sending final 200 OK on duplicate incoming INVITE transaction: " +
                        "callId=$incomingCallId cseq=$incomingCseq bytes=${duplicateFinalBytes.size}",
                )
                if (writeSipBytes(
                        incomingResponseWriter,
                        duplicateFinalBytes,
                        "duplicate incoming INVITE final 200 OK callId=$incomingCallId cseq=$incomingCseq",
                    )
                ) {
                    incomingFinalResponseSent.set(true)
                    incomingAcceptedAwaitingAck.set(true)
                } else {
                    Rlog.w(
                        TAG,
                        "Failed to send final 200 OK on duplicate incoming INVITE transaction: " +
                            "callId=$incomingCallId cseq=$incomingCseq",
                    )
                }
                return 0
            }

            return 100
        }

        val activeCallId = existingCall?.callHeaders?.get("call-id")?.getOrNull(0)
        if (existingCall != null && activeCallId != incomingCallId) {
            val activeDirection = if (existingCall.outgoing) "outgoing" else "incoming"
            val incomingCseq = request.headers["cseq"]?.getOrNull(0).orEmpty()
            Rlog.w(
                TAG,
                "Rejecting second incoming INVITE while busy: " +
                    "callId=$incomingCallId cseq=$incomingCseq " +
                    "activeCallId=$activeCallId activeDirection=$activeDirection"
            )
            rememberTerminatedIncomingCall(incomingCallId, "busy reject")
            return 486
        }

        val pendingOutgoingCallId = pendingOutgoingInvite?.callId
        if (pendingOutgoingCallId != null && pendingOutgoingCallId != incomingCallId) {
            val incomingCseq = request.headers["cseq"]?.getOrNull(0).orEmpty()
            Rlog.w(
                TAG,
                "Rejecting incoming INVITE while outgoing INVITE is pending: " +
                    "callId=$incomingCallId cseq=$incomingCseq " +
                    "pendingOutgoingCallId=$pendingOutgoingCallId"
            )
            rememberTerminatedIncomingCall(incomingCallId, "outgoing pending reject")
            return 486
        }

        callStopped.set(false)
        callStarted.set(false)
        threadsStarted.set(false)
        callGeneration.incrementAndGet()
        incomingFinalResponseSent.set(false)
        incomingAcceptedAwaitingAck.set(false)
        incomingHangupAfterAck.set(false)
        currentCall = null
        prAckWaitTracker.clearAndNotifyAll()

        val f = request.headers["from"]
        val m = extractCallerNumberFromHeader(f!![0]!!)
        Rlog.d(TAG, "Incoming call from $m rawFrom=${f[0]} callId=$incomingCallId hasIncomingResponseWriter=${requestWriters.containsKey(incomingCallId)}")

        // We'll have three states:
        // - 100 Trying (this will be done by returning 100 in this function)
        // - 183 Session Progress network-wise we're ready to receive data
        // - 180 Ringing Notification's AudioTrack is playing, the user can hear its phone -- Note: Ringing doesn't give SDP
        // - 200 User has accepted the call

        val sdp = request.body.toString(Charsets.UTF_8).split("[\r\n]+".toRegex()).toList()
        Rlog.d(TAG, "Split SDP into $sdp")
        fun sdpElement(command: String): String? {
            val v = sdp.firstOrNull { it.startsWith("$command=")} ?: return null
            return v.substring(2)
        }
        val sdpConnectionData = sdpElement("c")
        val sdpOrigin = sdpElement("o")
        val sdpSessionName = sdpElement("s")
        val sdpTiming = sdpElement("t")
        val sdpBandwidth = sdpElement("b")
        val sdpMedia = sdpElement("m")

        Rlog.d(TAG, "Got sdpTiming $sdpTiming")

        if (sdpTiming != "0 0")
            Rlog.d(TAG, "Uh-oh, unknown timing mode")


        val rtpRemote = sdpConnectionData!!.split(" ")[2] //c=IN IP6 xxx
        val rtpRemoteAddr = InetAddress.getByName(rtpRemote)
        val rtpRemotePort = sdpMedia!!.split(" ")[1] //m=audio 30798 RTP/AVP 96 97 98 8 18 101 100 99

        val attributes = sdp.filter { it.startsWith("a=") }.map { it.substring(2)}
        SipAudioCodecSdpLogger.logRemoteAudioCodecCandidates(
            tag = TAG,
            context = "remote SDP ${request.method} callId=${request.callIdOrEmpty()}",
            sdp = sdp,
        )

        fun lookTrackMatching(codec: String, additional: String = "", notAdditional: String = ""): Pair<Int,String>? {
            //TODO: also match on fmtp
            val maps = attributes.filter { it.startsWith("rtpmap") && it.contains(codec) }
            val matches = maps.map { m ->
                val track = m.split("[: ]+".toRegex())[1].toInt()
                val desc = m
                Pair(track, desc)
            }
            Rlog.d(TAG, "Matching $codec, got $matches")
            val matches2 = if(matches.size > 1) {
                matches.sortedBy { m ->
                    val fmtp = attributes.firstOrNull { it.startsWith("fmtp:${m.first}") }.orEmpty()
                    Rlog.d(TAG, "Matching $codec, for match $m got fmtp $fmtp")
                    when {
                        // For AMR, do not prefer an rtpmap-only payload when valid fmtp payloads exist.
                        codec.startsWith("AMR") && fmtp.isEmpty() -> 100

                        // This stack currently sends bandwidth-efficient AMR, so avoid octet-align=1.
                        notAdditional.isNotEmpty() && fmtp.contains(notAdditional) -> 90

                        // Optional positive preference for codecs/callers where we have one.
                        additional.isNotEmpty() && fmtp.contains(additional) -> 0

                        else -> 10
                    }
                }
            } else {
                matches
            }
            Rlog.d(TAG, "Matching2 $codec, got $matches2")
            return matches2.firstOrNull()
        }

        fun trackRequirements(track: Int): String? {
            return attributes.firstOrNull() { it.startsWith("fmtp:$track") }
        }

        val peerSupportsEarlyMedia = request.headers["p-early-media"]?.isNotEmpty() == true
        val callerCapabilityHeaders =
            request.headers["supported"].orEmpty() + request.headers["require"].orEmpty()
        val callerSupports100Rel = callerCapabilityHeaders
            .any { it.contains("100rel", ignoreCase = true) }
        val callerSupportsPreconditionHeader = callerCapabilityHeaders
            .any { it.contains("precondition", ignoreCase = true) }
        val incomingOfferHasPrecondition = attributes.any { attr ->
            attr.startsWith("curr:qos", ignoreCase = true) ||
                attr.startsWith("des:qos", ignoreCase = true) ||
                attr.startsWith("conf:qos", ignoreCase = true)
        }
        val incomingOfferIsInactive = attributes.any { it.equals("inactive", ignoreCase = true) }
        val callerSupportsPrecondition = callerSupportsPreconditionHeader || incomingOfferHasPrecondition
        // Some carriers send incoming VoLTE as inactive media with mandatory QoS
        // preconditions and will not open downlink RTP until the provisional SDP is
        // acknowledged with PRACK. Keep the old plain-180 path for simple incoming
        // offers because at least one tested network did not PRACK reliable 183.
        val sendReliable183 =
            callerSupports100Rel &&
                callerSupportsPrecondition &&
                incomingOfferHasPrecondition &&
                incomingOfferIsInactive
        val remoteMaxptime = attributes.firstOrNull { it.startsWith("maxptime:") } ?: "maxptime:20"
        Rlog.d(
            TAG,
            "Incoming early-media support=$peerSupportsEarlyMedia " +
                "sendReliable183=$sendReliable183 " +
                "supports100rel=$callerSupports100Rel " +
                "callerSupportsPrecondition=$callerSupportsPrecondition " +
                "headerPrecondition=$callerSupportsPreconditionHeader " +
                "sdpPrecondition=$incomingOfferHasPrecondition " +
                "inactiveOffer=$incomingOfferIsInactive " +
                "remoteMaxptime=$remoteMaxptime",
        )

        val selectedAudioCodec = SipAudioCodecNegotiator.selectIncomingSpeechCodecFromOffer(
            logTag = TAG,
            sdp = sdp,
            context = "incoming INVITE callId=$incomingCallId",
            amrWbMediaCodecAvailable = amrWbMediaCodecAvailable,
        )

        val (amrTrack, amrTrackDesc) = lookTrackMatching(
            SipAudioCodecNegotiator.speechCodecRtpmapName(selectedAudioCodec),
            additional = "",
            notAdditional = "octet-align=1",
        ) ?: return 488
        val amrTrackRequirements = trackRequirements(amrTrack)
        val amrFmtpAnswer = amrTrackRequirements ?: SipAudioCodecNegotiator.defaultSpeechFmtpAnswer(amrTrack, selectedAudioCodec)

        val (dtmfTrack, dtmfTrackDesc) =
            lookTrackMatching(SipAudioCodecNegotiator.telephoneEventRtpmapName(selectedAudioCodec)) ?: return 488

        val allTracks = listOf(amrTrack, dtmfTrack)
        val sdpBandwidthAs = SipAudioCodecNegotiator.sdpBandwidthAsKbps(selectedAudioCodec)
        // destination is sip:<owner>@realm, extract owner
        val owner = request.destination.substringAfter("sip:").substringBefore("@")

        val trying = SipResponse(
            statusCode = 100,
            statusString = "Trying",
            headersParam = responseHeadersFromRequest(
                request,
                extra = "Content-Length: 0".toSipHeadersMap(),
            ),
            autofill = false
        )
        Rlog.d(TAG, "Sending explicit 100 Trying on incoming request flow: $trying")
        synchronized(incomingResponseWriter) { incomingResponseWriter.write(trying.toByteArray()) }

        thread {
            // Need to sleep a bit so that our 100 Trying is sent first. Kinda weird.
            Thread.sleep(500)
            val rtpSocket = try {
                DatagramSocket(0, localAddr)
            } catch (t: Throwable) {
                Rlog.e(TAG, "Failed to bind incoming RTP socket to $localAddr; IMS address is likely stale", t)
                reconnectIms("incoming RTP bind failed for localAddr=$localAddr")
                return@thread
            }
            try {
                network.bindSocket(rtpSocket)
                rtpSocket.connect(rtpRemoteAddr, rtpRemotePort.toInt())
            } catch (t: Throwable) {
                Rlog.e(TAG, "Failed to bind/connect incoming RTP socket", t)
                try { rtpSocket.close() } catch (_: Throwable) {}
                reconnectIms("incoming RTP bind/connect failed")
                return@thread
            }
            Rlog.d(TAG, "RTP socket created: local=${rtpSocket.localAddress}:${rtpSocket.localPort}, remote=${rtpSocket.inetAddress}:${rtpSocket.port}")

            val local =
                if(socket.gLocalAddr() is Inet6Address)
                    "[${socket.gLocalAddr().hostAddress}]:${serverSocket.localPort}"
                else
                    "${socket.gLocalAddr().hostAddress}:${serverSocket.localPort}"
            val incomingDialogTransport = request.headers["via"]
                ?.firstOrNull()
                ?.substringAfter("SIP/2.0/", "")
                ?.substringBefore(" ")
                ?.trim()
                ?.lowercase()
                ?.takeIf { it == "udp" || it == "tcp" }
                ?: SipContactHeaders.transport(socket)
            val dialogContact = SipContactHeaders.mmtelContact(
                userPart = owner,
                localEndpoint = local,
                transport = incomingDialogTransport,
                sipInstance = SipContactHeaders.sipInstanceFromImei(imei),
            )
            Rlog.d(
                TAG,
                "Incoming dialog Contact: $dialogContact " +
                    "transport=$incomingDialogTransport callId=$incomingCallId",
            )
            val mySeqCounter = reliableSequenceCounter++
            val ipType = if(socket.gLocalAddr() is Inet6Address) "IP6" else "IP4"
            val sdpLines = mutableListOf(
                "v=0",
                "o=$owner 1 2 IN $ipType ${socket.gLocalAddr().hostAddress}",
                "s=phh voice call",
                "c=IN $ipType ${socket.gLocalAddr().hostAddress}",
                "b=AS:$sdpBandwidthAs",
                "b=RS:0",
                "b=RR:0",
                "t=0 0",
                "m=audio ${rtpSocket.localPort} RTP/AVP ${allTracks.joinToString(" ")}",
                "b=AS:$sdpBandwidthAs",
                "b=RS:0",
                "b=RR:0",
                "a=$amrTrackDesc",
                "a=ptime:20",
                "a=$remoteMaxptime",
                "a=$dtmfTrackDesc",
                "a=$amrFmtpAnswer",
                "a=fmtp:$dtmfTrack 0-15"
            )
            if (callerSupportsPrecondition) {
                val incomingCurrentQos = if (sendReliable183) "none" else "sendrecv"
                Rlog.d(
                    TAG,
                    "Incoming precondition SDP answer: callId=$incomingCallId " +
                        "sendReliable183=$sendReliable183 curr=$incomingCurrentQos",
                )
                sdpLines += listOf(
                    "a=curr:qos local $incomingCurrentQos",
                    "a=curr:qos remote $incomingCurrentQos",
                    "a=des:qos mandatory local sendrecv",
                    "a=des:qos mandatory remote sendrecv",
                    "a=conf:qos remote sendrecv"
                )
            }
            sdpLines += "a=sendrecv"
            /*
             * Keep generated incoming SDP bodies strictly CRLF-framed, including
             * the final line terminator. Some IMS SBCs reject/tear down the call
             * after the 200 OK when the SDP body lacks the trailing CRLF.
             */
            val mySdp = (sdpLines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.US_ASCII)

            // Generate a single local tag for all responses in this dialog (RFC 3261 §12.1.1).
            // Important for tel: URIs: without <> the appended ;tag can be parsed as a TEL URI
            // parameter instead of a SIP To header parameter, and the network may ignore our 200 OK.
            val localToTag = randomBytes(6).toHex()
            val toWithTag = request.headers["to"]!!.map { h -> SipHeaderTagger.addTag(h, localToTag) }
            Rlog.d(TAG, "Incoming To header normalized/tagged: ${request.headers["to"]!!} -> $toWithTag")

            val myHeaders = commonHeaders + //Require: precondition
                """
                        Contact: $dialogContact
                        Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, INFO, MESSAGE, PRACK, OPTIONS
                        Content-Type: application/sdp
                        Require: 100rel${if (callerSupportsPrecondition) ", precondition" else ""}
                        RSeq: $mySeqCounter
                        """.toSipHeadersMap() +
                            request.headers.filter { (k, _) -> k in listOf("cseq", "via", "from", "to", "call-id", "record-route") } +
                            mapOf("to" to toWithTag) -
                "route" - "security-verify"

            if (wasRecentlyTerminatedIncomingCall(incomingCallId)) {
                Rlog.w(TAG, "Aborting incoming call setup because Call-ID was terminated before dialog install: callId=$incomingCallId")
                try { rtpSocket.close() } catch (t: Throwable) { Rlog.d(TAG, "Closing aborted incoming RTP socket failed", t) }
                return@thread
            }
            currentCall = Call(
                outgoing = false,
                audioCodec = selectedAudioCodec,
                amrTrack = amrTrack,
                amrTrackDesc = amrTrackDesc,
                dtmfTrack = dtmfTrack,
                dtmfTrackDesc = dtmfTrackDesc,
                callHeaders = myHeaders - "require" - "content-type" - "p-access-network-info" + "Supported: replaces, timer".toSipHeadersMap(),
                rtpRemoteAddr = rtpRemoteAddr,
                rtpRemotePort = rtpRemotePort.toInt(),
                rtpSocket =  rtpSocket,
                sdp = mySdp,
                hasEarlyMedia = sendReliable183,
                remoteContact = extractDestinationFromContact(request.headers["contact"]!![0]),
                incomingResponseWriter = incomingResponseWriter,
            )
            val installedIncomingCall = currentCall
            val installedIncomingCallId = installedIncomingCall?.callIdOrEmpty().orEmpty()
            if (wasRecentlyTerminatedIncomingCall(incomingCallId) || installedIncomingCallId != incomingCallId || installedIncomingCall !== currentCall) {
                Rlog.w(TAG, "Aborting incoming ringing because Call-ID was terminated during setup: callId=$incomingCallId installed=$installedIncomingCallId")
                if (installedIncomingCallId == incomingCallId) {
                    currentCall = null
                }
                try { rtpSocket.close() } catch (t: Throwable) { Rlog.d(TAG, "Closing aborted incoming RTP socket failed", t) }
                return@thread
            }
            onIncomingCall?.invoke(
                Object(),
                m,
                mapOf("call-id" to incomingCallId) + SipAudioCodecNegotiator.audioCodecExtras(selectedAudioCodec),
            )

                        Rlog.d(TAG, "Deferring incoming media threads until final ACK")

            if (sendReliable183) {
                prAckWaitTracker.add(mySeqCounter)
                val msg =
                    SipResponse(
                        statusCode = 183,
                        statusString = "Session Progress",
                        headersParam = myHeaders,
                        body = mySdp
                    )
                Rlog.d(TAG, "Sending reliable incoming 183 for precondition offer: $msg")
                writeSipBytesWithFlush(incomingResponseWriter, "SipHandler msg", msg.toByteArray())
                waitPrack(mySeqCounter)
            } else {
                val myHeaders2 = myHeaders - "rseq" - "content-type" - "require" - "p-access-network-info" +
                    """
Supported: replaces, timer
Content-Length: 0

""".toSipHeadersMap()
                val msg2 =
                    SipResponse(
                        statusCode = 180,
                        statusString = "Ringing",
                        headersParam = myHeaders2,
                        autofill = false
                    )
                Rlog.d(TAG, "Sending plain 180 Ringing on incoming request flow, no reliable provisional response: $msg2")
                synchronized(incomingResponseWriter) { incomingResponseWriter.write(msg2.toByteArray()) }
            }
        }

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
