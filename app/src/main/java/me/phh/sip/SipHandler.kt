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

        private const val UPLINK_GAIN_PERSIST_PROPERTY = "persist.sys.phhims.uplink_gain_q8"
        private const val UPLINK_GAIN_RO_PROPERTY = "ro.phhims.uplink_gain_q8"
        private const val UPLINK_GAIN_UNSET_Q8 = 0
        private const val UPLINK_GAIN_UNITY_Q8 = 256
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


    private fun createVoiceCommunicationAudioRecord(
        bufferSize: Int,
        audioCodec: NegotiatedAudioCodec = SipAudioCodecs.AMR_NB,
    ): AudioRecord =
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            audioCodec.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )

    private val amrWbMediaCodecAvailable: Boolean by lazy {
        isMediaCodecAvailableFor(SipAudioCodecs.AMR_WB)
    }

    private fun isMediaCodecAvailableFor(audioCodec: NegotiatedAudioCodec): Boolean {
        var encoder: MediaCodec? = null
        var decoder: MediaCodec? = null
        return try {
            encoder = MediaCodec.createEncoderByType(audioCodec.mimeType)
            decoder = MediaCodec.createDecoderByType(audioCodec.mimeType)
            Rlog.d(TAG, "MediaCodec available for ${audioCodec.name}: mime=${audioCodec.mimeType}")
            true
        } catch (t: Throwable) {
            Rlog.w(TAG, "MediaCodec unavailable for ${audioCodec.name}: mime=${audioCodec.mimeType}", t)
            false
        } finally {
            try { encoder?.release() } catch (_: Throwable) { }
            try { decoder?.release() } catch (_: Throwable) { }
        }
    }

    private fun speechCodecRtpmapName(audioCodec: NegotiatedAudioCodec): String =
        "${audioCodec.sdpCodecName}/${audioCodec.rtpClockRate}"

    private fun telephoneEventRtpmapName(audioCodec: NegotiatedAudioCodec): String =
        "telephone-event/${audioCodec.rtpClockRate}"

    private fun defaultSpeechFmtpAnswer(track: Int, audioCodec: NegotiatedAudioCodec): String =
        if (audioCodec == SipAudioCodecs.AMR_WB) {
            "fmtp:$track octet-align=0;mode-change-capability=2;max-red=0"
        } else {
            "fmtp:$track mode-set=7;octet-align=0;max-red=0"
        }

    private fun sdpBandwidthAsKbps(audioCodec: NegotiatedAudioCodec): Int =
        if (audioCodec == SipAudioCodecs.AMR_WB) 88 else 38

    private fun audioCodecExtras(audioCodec: NegotiatedAudioCodec): Map<String, String> =
        mapOf(
            "audio-codec" to audioCodec.name,
            "audio-codec-rate" to audioCodec.sampleRate.toString(),
        )

    private fun selectIncomingSpeechCodecFromOffer(
        sdp: List<String>,
        context: String,
    ): NegotiatedAudioCodec {
        val candidates = SipAudioCodecSdpLogger.parseRemoteAudioCodecCandidates(sdp)
        val amrWbCandidate = SipAudioCodecSdpLogger.bestKnownWidebandCandidate(sdp)
        val amrNbCandidate = SipAudioCodecSdpLogger.bestCurrentlyImplementedCandidate(sdp)
        val hasAmrWbTelephoneEvent = candidates.any {
            it.codec == "TELEPHONE-EVENT" &&
                it.rate == SipAudioCodecs.AMR_WB.rtpClockRate
        }
        val amrWbUsable =
            amrWbCandidate != null &&
                !amrWbCandidate.fmtp.contains("octet-align=1", ignoreCase = true) &&
                hasAmrWbTelephoneEvent

        if (amrWbUsable && amrWbMediaCodecAvailable) {
            Rlog.w(
                TAG,
                "$context selecting AMR-WB/16000 candidate=${SipAudioCodecSdpLogger.describeRemoteAudioCodecCandidate(amrWbCandidate!!)} " +
                    "mediaCodecAvailable=$amrWbMediaCodecAvailable " +
                    "hasTelephoneEvent16000=$hasAmrWbTelephoneEvent",
            )
            return SipAudioCodecs.AMR_WB
        }

        Rlog.d(
            TAG,
            "$context selecting AMR-NB/8000 fallback " +
                "amrWbCandidate=${amrWbCandidate?.let { SipAudioCodecSdpLogger.describeRemoteAudioCodecCandidate(it) }} " +
                "amrWbUsable=$amrWbUsable " +
                "mediaCodecAvailable=$amrWbMediaCodecAvailable " +
                "hasTelephoneEvent16000=$hasAmrWbTelephoneEvent " +
                "amrNbCandidate=${amrNbCandidate?.let { SipAudioCodecSdpLogger.describeRemoteAudioCodecCandidate(it) }}",
        )
        return SipAudioCodecs.AMR_NB
    }

    private fun selectOutgoingSpeechCodecFromAnswer(
        sdp: List<String>,
        context: String,
    ): NegotiatedAudioCodec {
        val candidates = SipAudioCodecSdpLogger.parseRemoteAudioCodecCandidates(sdp)
        val amrWbCandidate = SipAudioCodecSdpLogger.bestKnownWidebandCandidate(sdp)
        val amrNbCandidate = SipAudioCodecSdpLogger.bestCurrentlyImplementedCandidate(sdp)
        val hasAmrWbTelephoneEvent = candidates.any {
            it.codec == "TELEPHONE-EVENT" &&
                it.rate == SipAudioCodecs.AMR_WB.rtpClockRate
        }
        val amrWbUsable =
            amrWbCandidate != null &&
                !amrWbCandidate.fmtp.contains("octet-align=1", ignoreCase = true) &&
                hasAmrWbTelephoneEvent

        if (amrWbUsable && amrWbMediaCodecAvailable) {
            Rlog.w(
                TAG,
                "$context outgoing answer selected AMR-WB/16000 candidate=${SipAudioCodecSdpLogger.describeRemoteAudioCodecCandidate(amrWbCandidate!!)} " +
                    "mediaCodecAvailable=$amrWbMediaCodecAvailable " +
                    "hasTelephoneEvent16000=$hasAmrWbTelephoneEvent",
            )
            return SipAudioCodecs.AMR_WB
        }

        Rlog.d(
            TAG,
            "$context outgoing answer selected AMR-NB/8000 fallback " +
                "amrWbCandidate=${amrWbCandidate?.let { SipAudioCodecSdpLogger.describeRemoteAudioCodecCandidate(it) }} " +
                "amrWbUsable=$amrWbUsable " +
                "mediaCodecAvailable=$amrWbMediaCodecAvailable " +
                "hasTelephoneEvent16000=$hasAmrWbTelephoneEvent " +
                "amrNbCandidate=${amrNbCandidate?.let { SipAudioCodecSdpLogger.describeRemoteAudioCodecCandidate(it) }}",
        )
        return SipAudioCodecs.AMR_NB
    }

    private val imsUplinkGainQ8: Int by lazy {
        val persistGain = android.os.SystemProperties.getInt(
            UPLINK_GAIN_PERSIST_PROPERTY,
            UPLINK_GAIN_UNSET_Q8,
        )

        val rawGain = if (persistGain != UPLINK_GAIN_UNSET_Q8) {
            persistGain
        } else {
            android.os.SystemProperties.getInt(
                UPLINK_GAIN_RO_PROPERTY,
                UPLINK_GAIN_UNITY_Q8,
            )
        }

        // Keep the property safe:
        // 128 = -6.0 dB, 256 = unity, 512 = +6.0 dB, 768 = +9.5 dB.
        rawGain.coerceIn(128, 768)
    }

    private fun applyImsUplinkGainInPlace(buffer: ByteArray, size: Int) {
        val gainQ8 = imsUplinkGainQ8
        if (gainQ8 == UPLINK_GAIN_UNITY_Q8 || size < 2) {
            return
        }

        var i = 0
        val end = size and -2

        while (i < end) {
            val sample = (buffer[i].toInt() and 0xff) or (buffer[i + 1].toInt() shl 8)
            val boosted = (sample * gainQ8) / UPLINK_GAIN_UNITY_Q8
            val clipped = boosted.coerceIn(
                Short.MIN_VALUE.toInt(),
                Short.MAX_VALUE.toInt(),
            )

            buffer[i] = (clipped and 0xff).toByte()
            buffer[i + 1] = ((clipped shr 8) and 0xff).toByte()

            i += 2
        }
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

    val isControlSocketUdp = carrierSettings.isControlSocketUdp
    val requireNonsessAka = carrierSettings.requireNonsessAka

    //private val realm = "ims.mnc$mnc.mcc$mcc.3gppnetwork.org"
    private val realm = "ims.mnc$mnc.mcc$mcc.3gppnetwork.org"
    private val user = "$imsi@$realm"
    private var akaDigest = ""
    private fun initialRegisterAuthorization(): String =
        """Digest username="$user",realm="$realm",nonce="",uri="sip:$realm",response="",algorithm=AKAv1-MD5"""


    fun generateCallId(): SipHeadersMap = SipCallIdGenerator.generate()

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
private val smsHandler = SipSmsHandler(
        tag = TAG,
        ctxt = ctxt,
        subId = subId,
        realmProvider = { realm },
        commonHeadersProvider = { commonHeaders },
        mySipProvider = { mySip },
        writerProvider = { socket.gWriter() },
        responseCallbackSetter = { callId, cb -> setResponseCallback(callId, cb) },
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

        restoreAudioModeAfterImsCall("stop runtime: $reason")
        runDeferredImsReconnectAfterCallTerminalState(reason)
    }

    private fun restoreAudioModeAfterImsCall(reason: String, previousMode: Int? = null) {
        val audioManager = try {
            ctxt.getSystemService(AudioManager::class.java)
        } catch (t: Throwable) {
            Rlog.d(TAG, "Audio mode restore skipped; AudioManager unavailable: $reason", t)
            return
        }

        val currentMode = audioManager.mode
        val wantedMode = when (previousMode ?: currentMode) {
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_RINGTONE -> AudioManager.MODE_NORMAL
            else -> previousMode ?: currentMode
        }

        if (currentMode == wantedMode) {
            Rlog.d(TAG, "Audio mode restore not needed: reason=$reason currentMode=$currentMode previousMode=$previousMode")
            return
        }

        Rlog.d(
            TAG,
            "Restoring audio mode after IMS call: reason=$reason " +
                "currentMode=$currentMode previousMode=$previousMode wantedMode=$wantedMode",
        )
        try {
            audioManager.clearCommunicationDevice()
        } catch (t: Throwable) {
            Rlog.d(TAG, "clearCommunicationDevice failed during IMS audio restore: $reason", t)
        }
        try {
            audioManager.mode = wantedMode
        } catch (t: Throwable) {
            Rlog.d(TAG, "Setting audio mode failed during IMS audio restore: $reason", t)
        }
    }

    private fun writeSipBytes(writer: OutputStream, bytes: ByteArray, label: String): Boolean {
        return SipMessageWriter.write(TAG, writer, bytes, label)
    }

    
    private fun sendRtpPacket(
        rtpSocket: DatagramSocket,
        bytes: ByteArray,
        remoteAddr: InetAddress,
        remotePort: Int,
        label: String,
    ): Boolean {
        return RtpPacketSender.send(TAG, rtpSocket, bytes, remoteAddr, remotePort, label)
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

    fun isReadyForOutgoingCall(): Boolean {
        val ready =
            imsReady &&
                !reconnectController.isReconnecting() &&
                this::network.isInitialized &&
                this::socket.isInitialized

        if (!ready) {
            Rlog.w(
                TAG,
                "Rejecting outgoing call while IMS is not stable: " +
                    "imsReady=$imsReady reconnecting=${reconnectController.isReconnecting()} " +
                    "networkInitialized=${this::network.isInitialized} " +
                    "socketInitialized=${this::socket.isInitialized}",
            )
        }

        return ready
    }

    fun getRegistrationTech(): Int = imsRegistrationTech

    fun handlesSubscription(candidateSubId: Int): Boolean = subId == candidateSubId

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


    
    private fun ratName(rat: Int): String =
        ImsNetworkState.ratName(rat)

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
        Rlog.d(TAG, "Trying to connect to SIP server")
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

        Rlog.w(TAG, "Connecting with address $localAddr to $pcscfAddr")

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

        Rlog.d(TAG, "Src port is ${socket.gLocalPort()}, TCP server port is ${serverSocket.localPort}, UDP server port is ${serverSocketUdp.localPort}")
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

        akaDigest = SipRegistrationDigestFactory.create(
            user = user,
            realm = registerChallenge.realm,
            uri = "sip:$realm",
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
                val writer = ByteArrayOutputStream()

                while (true) {
                    dgramPacketIn.length = bufferIn.size
                    serverSocketUdp.socket.receive(dgramPacketIn)
                    Rlog.d(TAG, "Received dgram packet")

                    val baIs = ByteArrayInputStream(dgramPacketIn.data, dgramPacketIn.offset, dgramPacketIn.length)
                    val reader = baIs.sipReader()
                    while (parseMessage(reader, writer)) {
                    }

                    val writerOut = writer.toByteArray()
                    val dgramPacketOut = DatagramPacket(writerOut, writerOut.size, dgramPacketIn.address, dgramPacketIn.port)
                    serverSocketUdp.socket.send(dgramPacketOut)
                    writer.reset()
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
        Rlog.d(TAG, "Requesting IMS network for slotId=$slotId subId=$subId")
        if (!isRatReadyForImsNetworkRequest()) {
            Rlog.w(TAG, "Deferring IMS network request until LTE/NR/IWLAN is back")
            scheduleImsNetworkRequestRestart("RAT not ready for IMS network request")
            return
        }
        val imsNetworkRequest = ImsNetworkRequestBuilder.buildForSubscription(subId)

        Rlog.d(TAG, "Built subscription-specific IMS network request $imsNetworkRequest")

        unregisterImsNetworkCallback("new IMS network request")

        val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onUnavailable() {
                    Rlog.d(TAG, "IMS network unavailable")
                }

                override fun onLost(lostNetwork: Network) {
                    Rlog.d(TAG, "IMS network lost $lostNetwork")
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
                    Rlog.d(TAG, "IMS network blocked status changed $blocked")
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    Rlog.d(TAG, "IMS network capabilities changed $networkCapabilities")
                    if (
                        this@SipHandler::network.isInitialized &&
                            network == this@SipHandler.network &&
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
                    Rlog.d(TAG, "IMS network link properties changed $linkProperties")
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
                    Rlog.d(TAG, "Got IMS network $_network")
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
            realm = realm,
            registerHeaders = registerHeaders,
            registerCounter = registerCounter,
            contact = contact,
            akaDigest = akaDigest,
            ipsecSettings = ipsecSettings,
            clientPort = socket.gLocalPort(),
            serverPort = serverSocket.localPort,
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
        synchronized(socket.gWriter()) { socket.gWriter().write(msg.toByteArray()) }
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
                callEncodeThread()
            } else {
                Rlog.d(TAG, "Incoming media threads already started before final ACK")
            }

            onIncomingCallConnected?.invoke(
                Object(),
                mapOf("call-id" to callId) + audioCodecExtras(call.audioCodec),
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
        val amr = lookTrackMatching(speechCodecRtpmapName(selectedAudioCodec), notAdditional = "octet-align=1")
        if (amr == null) {
            Rlog.w(TAG, "Rejecting UPDATE: no compatible ${speechCodecRtpmapName(selectedAudioCodec)} payload in offer callId=$requestCallId offered=$offeredPayloads")
            return 488
        }
        val (amrTrack, amrTrackDesc) = amr
        val amrFmtpAnswer = trackRequirements(amrTrack)
            ?: defaultSpeechFmtpAnswer(amrTrack, selectedAudioCodec)

        val dtmf = lookTrackMatching(telephoneEventRtpmapName(selectedAudioCodec))
        if (dtmf == null) {
            Rlog.w(TAG, "Rejecting UPDATE: no compatible ${telephoneEventRtpmapName(selectedAudioCodec)} payload in offer callId=$requestCallId offered=$offeredPayloads")
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
        val sdpBandwidthAs = sdpBandwidthAsKbps(selectedAudioCodec)

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
        val cancelSent: AtomicBoolean = AtomicBoolean(false),
    )

    // AMR-NB speech payload sizes in bits for FT 0..8.
    // Codec input for Android's audio/3gpp decoder is one AMR storage frame:
    //   [frame header: 0 | FT(4) | Q | 00] + speech bits octet padded.
    // The RTP payloads used here are RFC 4867 bandwidth-efficient packets:
    //   CMR(4), F(1), FT(4), Q(1), speech bits...
    fun callEncodeThread(
        incomingMicStartDelayMs: Long = 0L,
        reason: String = "default",
    ) {
        val call = currentCall!!
        val audioCodec = call.audioCodec
        val gen = callGeneration.get()
        thread {
            rtpSequenceNumber.set(0)
            rtpTimestampSamples.set(0)
            rtpDtmfTimestampSamples.set(0)
            Rlog.d(TAG, "Encode thread started: codec=${audioCodec.name}/${audioCodec.sampleRate} amrTrack=${call.amrTrack} remote=${call.rtpRemoteAddr}:${call.rtpRemotePort} gen=$gen")
            val encoder = MediaCodec.createEncoderByType(audioCodec.mimeType)
            val mediaFormat = MediaFormat.createAudioFormat(
                audioCodec.mimeType,
                audioCodec.sampleRate,
                audioCodec.channelCount,
            )
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioCodec.bitRate)
            mediaFormat.setInteger(MediaFormat.KEY_PRIORITY, 0) //  0 = realtime priority, encoder will not fall behind
            encoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            while(!callStarted.get()) {
                if (callStopped.get() || callGeneration.get() != gen) {
                    Rlog.d(TAG, "Silence loop exiting early: callStopped=${callStopped.get()}, genMismatch=${callGeneration.get() != gen}")
                    encoder.stop()
                    encoder.release()
                    return@thread
                }
                val sequenceNumber = rtpSequenceNumber.getAndIncrement()
                val timestamp = rtpTimestampSamples.getAndAdd(audioCodec.rtpTimestampStep)
                Thread.sleep(20)
                val sendCall = currentCall ?: call
                val buf = SipAmrRtpPayload.buildNoDataRtpPacket(
                    audioCodec = audioCodec,
                    payloadType = sendCall.amrTrack,
                    sequenceNumber = sequenceNumber,
                    timestamp = timestamp,
                )
                try {
                    if (!sendRtpPacket(sendCall.rtpSocket, buf, sendCall.rtpRemoteAddr, sendCall.rtpRemotePort, "RTP packet #$sequenceNumber")) throw IOException("RTP send failed")
                } catch (e: Exception) {
                    Rlog.w(TAG, "Silence RTP send failed, stopping encode thread: ${e.message}", e)
                    encoder.stop()
                    encoder.release()
                    return@thread
                }
                }
                Rlog.d(TAG, "Silence loop exited after ${rtpSequenceNumber.get()} packets, starting real encoding")
            if (!call.outgoing && incomingMicStartDelayMs > 0L) {
                val settleDeadline = System.currentTimeMillis() + incomingMicStartDelayMs
                var settlePackets = 0
                Rlog.d(
                    TAG,
                    "Delaying incoming AudioRecord start by ${incomingMicStartDelayMs}ms after ACK: reason=$reason gen=$gen",
                )
                while (System.currentTimeMillis() < settleDeadline) {
                    if (callStopped.get() || callGeneration.get() != gen) {
                        Rlog.d(
                            TAG,
                            "Incoming mic delay exiting early: callStopped=${callStopped.get()} genMismatch=${callGeneration.get() != gen}",
                        )
                        try {
                            encoder.stop()
                        } catch (_: Throwable) {
                        }
                        try {
                            encoder.release()
                        } catch (_: Throwable) {
                        }
                        return@thread
                    }
            
                    val sequenceNumber = rtpSequenceNumber.getAndIncrement()
            
                    val timestamp = rtpTimestampSamples.getAndAdd(audioCodec.rtpTimestampStep)
                    val sendCall = currentCall ?: call
                    val buf = SipAmrRtpPayload.buildNoDataRtpPacket(
                        audioCodec = audioCodec,
                        payloadType = sendCall.amrTrack,
                        sequenceNumber = sequenceNumber,
                        timestamp = timestamp,
                    )
                    try {
                        if (!sendRtpPacket(sendCall.rtpSocket, buf, sendCall.rtpRemoteAddr, sendCall.rtpRemotePort, "incoming RTP settle silence #$sequenceNumber")) {
                            throw IOException("RTP send failed")
                        }
                    } catch (e: Exception) {
                        Rlog.w(TAG, "Incoming RTP settle silence failed, stopping encode thread: ${e.message}", e)
                        try {
                            encoder.stop()
                        } catch (_: Throwable) {
                        }
                        try {
                            encoder.release()
                        } catch (_: Throwable) {
                        }
                        return@thread
                    }
                    settlePackets++
                    Thread.sleep(20)
                }
                Rlog.d(TAG, "Incoming AudioRecord delay complete after $settlePackets packets; starting real encoding")
            }

            // DANGER: Don't open the mic before the user acknowledged opening the call!

            val minBufferSize = AudioRecord.getMinBufferSize(
                audioCodec.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufferSize <= 0) {
                Rlog.e(TAG, "AudioRecord.getMinBufferSize failed: $minBufferSize")
                try { encoder.stop() } catch (_: Throwable) { }
                try { encoder.release() } catch (_: Throwable) { }
                return@thread
            }
            val audioRecord = try {
                createVoiceCommunicationAudioRecord(minBufferSize, audioCodec)
            } catch (t: Throwable) {
                Rlog.e(TAG, "AudioRecord creation failed with bufferSize=$minBufferSize", t)
                try { encoder.stop() } catch (_: Throwable) { }
                try { encoder.release() } catch (_: Throwable) { }
                return@thread
            }
            Rlog.d(TAG, "AudioRecord created with minBufferSize=$minBufferSize, state=${audioRecord.state}")

            // Pin capture to the built-in mic so the Samsung HAL cannot reroute it to
            // the baseband PCM path (pcmC0D110c) that produces silence for software IMS.
            // setPreferredDevice overrides HAL source-based routing while keeping
            // VOICE_COMMUNICATION semantics (call-mode output path stays correct).
            val audioManager = ctxt.getSystemService(android.media.AudioManager::class.java)
            val builtinMic = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            if (builtinMic != null) {
                audioRecord.preferredDevice = builtinMic
                Rlog.d(TAG, "AudioRecord preferredDevice set to builtin mic: id=${builtinMic.id} name=${builtinMic.productName}")
            } else {
                Rlog.w(TAG, "AudioRecord: no TYPE_BUILTIN_MIC found, proceeding without preferredDevice")
            }

            val prevAudioMode = audioManager.mode
            audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            try {
                audioRecord.startRecording()
            } catch (t: Throwable) {
                Rlog.e(TAG, "AudioRecord.startRecording failed", t)
                try { audioRecord.release() } catch (_: Throwable) { }
                try { encoder.stop() } catch (_: Throwable) { }
                try { encoder.release() } catch (_: Throwable) { }
                return@thread
            }
            Rlog.d(TAG, "AudioRecord started, state=${audioRecord.recordingState} audioMode=${audioManager.mode} (was $prevAudioMode) preferredDevice=${audioRecord.preferredDevice?.type}")
            Rlog.d(
                TAG,
                "IMS uplink gain q8=$imsUplinkGainQ8 " +
                    "persist=$UPLINK_GAIN_PERSIST_PROPERTY ro=$UPLINK_GAIN_RO_PROPERTY",
            )

            var firstPacket = true
            var realFrameCount = 0

            val buffer = ByteArray(minBufferSize)
            while (true) {
                if (callStopped.get() || callGeneration.get() != gen) break
                val nRead = audioRecord.read(buffer, 0, buffer.size)
                if (realFrameCount < 5) {
                    val allZero = buffer.take(nRead.coerceAtLeast(0)).all { it == 0.toByte() }
                    Rlog.d(TAG, "AudioRecord.read nRead=$nRead allZero=$allZero (bufferSize=${buffer.size})")
                }

                val inBufIdx = encoder.dequeueInputBuffer(-1)
                val inBuf = encoder.getInputBuffer(inBufIdx)!!
                inBuf.clear()
                if (nRead > 0) {
                    applyImsUplinkGainInPlace(buffer, nRead)
                }
                inBuf.put(buffer, 0, nRead)

                // Fake timestamp but it is not appearing in the output stream anyway
                encoder.queueInputBuffer(inBufIdx, 0, nRead, System.nanoTime() / 1000, 0)

                // Drain all output frames the encoder produced for this input.
                // Use -1 (block) on the first call so we always wait for the async
                // C2 encoder to finish; use 0 on subsequent calls to collect any
                // additional frames without stalling.  Without draining, the output
                // queue fills up and dequeueInputBuffer(-1) deadlocks.
                val outBufInfo = MediaCodec.BufferInfo()
                var drainTimeout = -1L
                var outCount = 0
                while (true) {
                    val outBufIdx = encoder.dequeueOutputBuffer(outBufInfo, drainTimeout)
                    drainTimeout = 0L
                    if (outBufIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Rlog.d(TAG, "Encoder output format changed")
                        continue
                    }
                    if (outBufIdx < 0) {
                        if (outCount > 0) Rlog.d(TAG, "Drained $outCount output buffers")
                        break
                    }
                    outCount++

                    val outBuf = encoder.getOutputBuffer(outBufIdx)!!

                    val encoderData = ByteArray(outBufInfo.size)
                    outBuf.get(encoderData)
                    encoder.releaseOutputBuffer(outBufIdx, false)

                    if (realFrameCount == 0) {
                        Rlog.d(TAG, "First encoder output: size=${outBufInfo.size} raw=${encoderData.take(32).joinToString(" ") { "%02x".format(it) }}")
                    }

                    var bufPos = 0
                    while (bufPos < outBufInfo.size) {
                        val ft = (encoderData[bufPos].toUByte().toInt() shr 3) and 0xf
                        val frameSize = SipAmrRtpPayload.storageFrameSizeBytes(audioCodec, ft)
                        if (frameSize == null) {
                            Rlog.w(TAG, "Skipping encoder frame with unsupported AMR FT=$ft codec=${audioCodec.name}")
                            break
                        }
                        if (outBufInfo.size - bufPos < frameSize) break

                        val sequenceNumber = rtpSequenceNumber.getAndIncrement()
                        val timestamp = rtpTimestampSamples.getAndAdd(audioCodec.rtpTimestampStep)
                        val sendCall = currentCall ?: break
                        val storageFrame = encoderData.copyOfRange(bufPos, bufPos + frameSize)
                        val buf = SipAmrRtpPayload.buildBandwidthEfficientRtpPacketFromStorageFrame(
                            audioCodec = audioCodec,
                            payloadType = sendCall.amrTrack,
                            sequenceNumber = sequenceNumber,
                            timestamp = timestamp,
                            storageFrame = storageFrame,
                            marker = firstPacket,
                        )
                        if (buf == null) {
                            Rlog.w(TAG, "Failed to build AMR RTP packet: codec=${audioCodec.name} ft=$ft frameSize=$frameSize")
                            break
                        }
                        firstPacket = false
                        try {
                            if (!sendRtpPacket(sendCall.rtpSocket, buf, sendCall.rtpRemoteAddr, sendCall.rtpRemotePort, "RTP packet #$sequenceNumber")) throw IOException("RTP send failed")
                            if (realFrameCount < 10) {
                                Rlog.d(TAG, "Sent RTP packet #$sequenceNumber ft=$ft ts=$timestamp payload=${buf.drop(12).take(4).joinToString(" ") { "%02x".format(it) }}... to ${sendCall.rtpRemoteAddr}:${sendCall.rtpRemotePort}")
                            }
                            if (realFrameCount == 0) {
                                Rlog.d(TAG, "First RTP packet full hex: ${buf.joinToString(" ") { "%02x".format(it) }}")
                            }
                            if (sequenceNumber % 50 == 0 && realFrameCount >= 10) {
                                Rlog.d(TAG, "Sent RTP packet #$sequenceNumber ft=$ft ts=$timestamp to ${sendCall.rtpRemoteAddr}:${sendCall.rtpRemotePort}")
                            }
                        } catch (e: Exception) {
                            Rlog.e(TAG, "Failed to send RTP packet #$sequenceNumber: ${e.message}", e)
                        }

                        realFrameCount++
                        bufPos += frameSize
                    }
                }
            }
            Rlog.d(TAG, "Encode thread exiting: callStopped=${callStopped.get()}, genMismatch=${callGeneration.get() != gen}, totalPacketsSent=${rtpSequenceNumber.get()}")
            try { audioRecord.stop() } catch (t: Throwable) { Rlog.d(TAG, "AudioRecord stop failed during encode cleanup", t) }
            try { audioRecord.release() } catch (t: Throwable) { Rlog.d(TAG, "AudioRecord release failed during encode cleanup", t) }
            try { encoder.stop() } catch (t: Throwable) { Rlog.d(TAG, "Encoder stop failed during encode cleanup", t) }
            try { encoder.release() } catch (t: Throwable) { Rlog.d(TAG, "Encoder release failed during encode cleanup", t) }
            Rlog.d(TAG, "Encode thread cleanup complete before audio mode restore: callStopped=${callStopped.get()} genMismatch=${callGeneration.get() != gen}")
            restoreAudioModeAfterImsCall("encode thread cleanup", previousMode = prevAudioMode)
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
                audioCodecExtras(call.audioCodec),
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
                    if (!writeSipBytes(responseWriter, responseBytes, "incoming INVITE final 200 OK retransmit callId=$acceptedCallId elapsed=${elapsedMs}ms")) {
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
        synchronized(socket.gWriter()) { socket.gWriter().write(msg.toByteArray()) }
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
            synchronized(responseWriter) { responseWriter.write(msg.toByteArray()) }

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
        synchronized(socket.gWriter()) { socket.gWriter().write(cancel.toByteArray()) }
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
        synchronized(socket.gWriter()) { socket.gWriter().write(bye.toByteArray()) }
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
            rememberTerminatedIncomingCall(call.callIdOrEmpty(), "local pre-ACK hangup")
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
                Rlog.e(TAG, "Failed to bind outgoing RTP socket to $localAddr; IMS address is likely stale", t)
                reconnectIms("outgoing RTP bind failed for localAddr=$localAddr")
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
            rtpSocket.soTimeout = 2000
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
                sdpBandwidthAsKbps(SipAudioCodecs.AMR_WB)
            } else {
                sdpBandwidthAsKbps(SipAudioCodecs.AMR_NB)
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
           val sdp = (sdpLines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.US_ASCII)

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
                    Session-Expires: 900
                    Supported: 100rel, replaces, timer, precondition
                    Accept: application/sdp
                    Min-SE: 90
                    Accept-Contact: *;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel"
                    P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.mmtel
                    Contact: $contactTel
                    """.toSipHeadersMap() + generateCallId() - "p-asserted-identity"
            // P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.mmtel
            // Accept-Contact: *;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel"
            val msg =
                SipRequest(
                    SipMethod.INVITE,
                    to,
                    myHeaders,
                    sdp
                )
            val outgoingInviteCallId = msg.headers["call-id"]!![0]
            val outgoingInviteCseq = msg.headers["cseq"]?.getOrNull(0)
                ?.substringBefore(" ")
                ?.toIntOrNull()
                ?: 1
            val outgoingDialogNextCseq = AtomicInteger(outgoingInviteCseq + 1)
            pendingOutgoingInvite = PendingOutgoingInvite(
                callId = outgoingInviteCallId,
                destination = to,
                headers = msg.headers,
                rtpSocket = rtpSocket,
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
                        val failedCallId = resp.callIdOrEmpty()
                        val failedCseq = resp.headers["cseq"]?.getOrNull(0).orEmpty()
                        val activeCallId = currentCall?.callIdOrNull()
                        val pendingCallId = pendingOutgoingInvite?.callId

                        if (activeCallId != failedCallId && pendingCallId != failedCallId) {
                            Rlog.w(TAG, "Ignoring stale outgoing dialog failure: status=${resp.statusCode} ${resp.statusString} cseq=$failedCseq callId=$failedCallId active=$activeCallId pending=$pendingCallId")
                            return@setResponseCallback true
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

                val selectedAudioCodec = selectOutgoingSpeechCodecFromAnswer(
                    sdp = respSdp,
                    context = "outgoing SDP response ${resp.statusCode} callId=${resp.callIdOrEmpty()}",
                )
                val selectedAmr = lookResponseTrackMatching(
                    speechCodecRtpmapName(selectedAudioCodec),
                    notAdditional = "octet-align=1",
                )
                if (selectedAmr == null) {
                    Rlog.w(
                        TAG,
                        "Outgoing SDP response lacks compatible ${speechCodecRtpmapName(selectedAudioCodec)}; " +
                            "falling back to AMR-NB/8000 tracks",
                    )
                }
                val selectedDtmf = lookResponseTrackMatching(
                    telephoneEventRtpmapName(selectedAudioCodec),
                )
                if (selectedDtmf == null) {
                    Rlog.w(
                        TAG,
                        "Outgoing SDP response lacks compatible ${telephoneEventRtpmapName(selectedAudioCodec)}; " +
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
                        synchronized(socket.gWriter()) { socket.gWriter().write(msg2.toByteArray()) }
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
            Rlog.d(TAG, "Sending $msg")
            synchronized(socket.gWriter()) { socket.gWriter().write(msg.toByteArray()) }
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
            val minBufferSize = AudioTrack.getMinBufferSize(
                audioCodec.sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val audioTrack = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                audioCodec.sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize,
                AudioTrack.MODE_STREAM,
            )
            audioTrack.play()
            // PhhIms downlink PCM playout smoother: decouple RTP receive jitter
            // from AudioTrack writes. IVR/transfer gateways can burst packets or
            // send sparse SID/CN frames after DTMF; writing only when RTP arrives
            // lets AudioTrack underrun and sounds like heavy stutter. Keep a tiny
            // 20ms playout loop and feed silence when the decoder has no PCM ready.
            val downlinkFrameBytes = ((audioCodec.sampleRate / 50) * audioCodec.channelCount * 2).coerceAtLeast(320)
            val downlinkSilenceFrame = ByteArray(downlinkFrameBytes)
            val downlinkPcmQueue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(8)
            val downlinkPlayoutRunning = java.util.concurrent.atomic.AtomicBoolean(true)
            val downlinkPlayoutThread = thread(name = "PhhDownlinkPcmPlayout") {
                var fillerFrames = 0
                var nextWriteAtMs = android.os.SystemClock.elapsedRealtime() + 60L
                Rlog.d(TAG, "Downlink PCM playout started: frameBytes=$downlinkFrameBytes codec=${audioCodec.name}/${audioCodec.sampleRate} gen=$gen")
                try {
                    while (downlinkPlayoutRunning.get() && !callStopped.get() && callGeneration.get() == gen) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        val sleepMs = nextWriteAtMs - now
                        if (sleepMs > 0L) Thread.sleep(sleepMs.coerceAtMost(40L))
            
                        val pcm = downlinkPcmQueue.poll() ?: downlinkSilenceFrame
                        if (pcm === downlinkSilenceFrame) {
                            fillerFrames++
                            if (fillerFrames == 1 || fillerFrames % 50 == 0) {
                                Rlog.d(TAG, "Downlink PCM playout filler frames=$fillerFrames queued=${downlinkPcmQueue.size} gen=$gen")
                            }
                        } else if (fillerFrames > 0) {
                            Rlog.d(TAG, "Downlink PCM playout recovered after fillerFrames=$fillerFrames queued=${downlinkPcmQueue.size} gen=$gen")
                            fillerFrames = 0
                        }
            
                        audioTrack.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                        nextWriteAtMs += 20L
                        val afterWriteMs = android.os.SystemClock.elapsedRealtime()
                        if (afterWriteMs - nextWriteAtMs > 200L) {
                            nextWriteAtMs = afterWriteMs + 20L
                        }
                    }
                } catch (_: InterruptedException) {
                    // Normal during call teardown.
                } catch (t: Throwable) {
                    Rlog.w(TAG, "Downlink PCM playout failed", t)
                }
                Rlog.d(TAG, "Downlink PCM playout exiting: running=${downlinkPlayoutRunning.get()} callStopped=${callStopped.get()} genMismatch=${callGeneration.get() != gen} queued=${downlinkPcmQueue.size}")
            }

            val decoder = MediaCodec.createDecoderByType(audioCodec.mimeType)
            val mediaFormat = MediaFormat.createAudioFormat(
                audioCodec.mimeType,
                audioCodec.sampleRate,
                audioCodec.channelCount,
            )
            decoder.configure(mediaFormat, null, null, 0)
            decoder.start()

            var receivedCount = 0
            while(true) {
                if (callStopped.get() || callGeneration.get() != gen) break
                val dgramBuf = ByteArray(2048)
                val dgram = DatagramPacket(dgramBuf, dgramBuf.size)
                val receiveCall = currentCall ?: break
                try {
                receiveCall.rtpSocket.receive(dgram)
            } catch (e: SocketTimeoutException) {
                Rlog.w(TAG, "RTP receive timeout: outgoing=${receiveCall.outgoing} local=${receiveCall.rtpSocket.localAddress}:${receiveCall.rtpSocket.localPort} connected=${receiveCall.rtpSocket.isConnected} remote=${receiveCall.rtpRemoteAddr}:${receiveCall.rtpRemotePort} callStopped=${callStopped.get()} genMismatch=${callGeneration.get() != gen}")
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
                val pt = dgramBuf[1].toUByte().toInt() and 0x7f
                val amrFrame = SipAmrRtpPayload.storageFrameFromBandwidthEfficientRtp(audioCodec, dgramBuf, dgram.length)
                val ftForLog = amrFrame?.ft ?: 15

                if (receivedCount <= 10 || receivedCount % 50 == 0) {
                    Rlog.d(TAG, "Received RTP packet #$receivedCount: from=${dgram.address}:${dgram.port} length=${dgram.length} pt=$pt ft=$ftForLog codecBytes=${amrFrame?.codecFrame?.size ?: 0}")
                }

                if (amrFrame == null) continue

                val inBufIndex = decoder.dequeueInputBuffer(-1)
                val inBuf = decoder.getInputBuffer(inBufIndex)!!
                val data = amrFrame.codecFrame
                inBuf.clear()
                inBuf.put(data)
                decoder.queueInputBuffer(inBufIndex, 0, data.size, 0, 0)

                // Drain decoder output.  Some AMR modes do not produce an output buffer
                // immediately with a zero-timeout dequeue on all codecs, so give it a tiny
                // real-time budget for the first buffer and then drain anything else.
                val outBufInfo = MediaCodec.BufferInfo()
                var drainTimeoutUs = 10_000L
                while (true) {
                    val outBufIndex = decoder.dequeueOutputBuffer(outBufInfo, drainTimeoutUs)
                    drainTimeoutUs = 0L
                    if (outBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Rlog.d(TAG, "Decoder output format changed")
                        continue
                    }
                    if (outBufIndex < 0) break

                    val outBuf = decoder.getOutputBuffer(outBufIndex)!!
                    val pcm = ByteArray(outBufInfo.size)
                    outBuf.position(outBufInfo.offset)
                    outBuf.limit(outBufInfo.offset + outBufInfo.size)
                    outBuf.get(pcm)
                    if (!downlinkPcmQueue.offer(pcm)) {
                        downlinkPcmQueue.poll()
                        if (!downlinkPcmQueue.offer(pcm)) {
                            Rlog.w(TAG, "Downlink PCM queue still full after dropping oldest frame")
                        }
                    }
                    decoder.releaseOutputBuffer(outBufIndex, false)
                }
            }
            downlinkPlayoutRunning.set(false)
            try { downlinkPlayoutThread.interrupt() } catch (t: Throwable) { Rlog.d(TAG, "Downlink playout interrupt failed during decode cleanup", t) }
            try { audioTrack.stop() } catch (t: Throwable) { Rlog.d(TAG, "AudioTrack stop failed during decode cleanup", t) }
            try { audioTrack.release() } catch (t: Throwable) { Rlog.d(TAG, "AudioTrack release failed during decode cleanup", t) }
            try { decoder.stop() } catch (t: Throwable) { Rlog.d(TAG, "Decoder stop failed during decode cleanup", t) }
            try { decoder.release() } catch (t: Throwable) { Rlog.d(TAG, "Decoder release failed during decode cleanup", t) }
            restoreAudioModeAfterImsCall("decode thread cleanup", previousMode = prevDecodeAudioMode)
            Rlog.d(TAG, "Decode thread cleanup complete: callStopped=${callStopped.get()} genMismatch=${callGeneration.get() != gen} received=$receivedCount")
        }
    }

    private fun allocateDtmfTimestampSamples(audioCodec: NegotiatedAudioCodec, durationMs: Int): Int {
        val safeDurationMs = durationMs.coerceAtLeast(160)
        // One telephone-event uses one fixed timestamp for all repeats, but the
        // next digit must not reuse that timestamp. Keep at least one event
        // duration plus 40ms between synthetic timestamps when media is stalled.
        val minimumStepSamples = ((safeDurationMs + 40) * audioCodec.sampleRate) / 1000
        while (true) {
            val mediaTimestamp = rtpTimestampSamples.get()
            val previousDtmfTimestamp = rtpDtmfTimestampSamples.get()
            val candidate = if (previousDtmfTimestamp <= 0) {
                mediaTimestamp.coerceAtLeast(audioCodec.rtpTimestampStep)
            } else {
                maxOf(mediaTimestamp, previousDtmfTimestamp + minimumStepSamples)
            }
            if (rtpDtmfTimestampSamples.compareAndSet(previousDtmfTimestamp, candidate)) {
                return candidate
            }
        }
    }

    fun sendDtmf(c: Char, durationMs: Int = 160) {
        val call = currentCall
        if (call == null) {
            Rlog.w(TAG, "sendDtmf without current call")
            return
        }
        val event = when (c.uppercaseChar()) {
            '0','1','2','3','4','5','6','7','8','9' -> c.digitToInt()
            '*' -> 10
            '#' -> 11
            'A' -> 12
            'B' -> 13
            'C' -> 14
            'D' -> 15
            else -> {
                Rlog.w(TAG, "Ignoring unsupported DTMF char: $c")
                return
            }
        }

        thread {
            try {
                // RFC 4733 telephone-event. Keep one RTP timestamp for the whole event,
                // increase duration, and repeat the final packet with the E bit set.
                val dtmfCall = currentCall ?: call
                val timestamp = allocateDtmfTimestampSamples(dtmfCall.audioCodec, durationMs)
                val durationSamples = (durationMs.coerceAtLeast(160) * dtmfCall.audioCodec.sampleRate) / 1000
                val steps = listOf(
                    durationSamples / 4,
                    durationSamples / 2,
                    durationSamples,
                    durationSamples,
                    durationSamples,
                    durationSamples,
                )
                Rlog.d(TAG, "Sending RTP DTMF event=$event char=$c payload=${dtmfCall.dtmfTrack} durationMs=$durationMs timestamp=$timestamp sequenceBase=${rtpSequenceNumber.get()} remote=${dtmfCall.rtpRemoteAddr}:${dtmfCall.rtpRemotePort}")
                for ((index, duration) in steps.withIndex()) {
                    val sendCall = currentCall ?: return@thread
                    val sequenceNumber = rtpSequenceNumber.getAndIncrement()
                    val marker = if (index == 0) 0x80 else 0x00
                    val end = if (index >= 3) 0x80 else 0x00
                    val volume = 10
                    val rtpHeader = byteArrayOf(
                        0x80.toByte(),
                        (marker or sendCall.dtmfTrack).toByte(),
                        (sequenceNumber shr 8).toByte(), (sequenceNumber and 0xff).toByte(),
                        (timestamp shr 24).toByte(), ((timestamp shr 16) and 0xff).toByte(),
                        ((timestamp shr 8) and 0xff).toByte(), (timestamp and 0xff).toByte(),
                        0x03, 0x00, 0xd2.toByte(), 0x00,
                    )
                    val payload = byteArrayOf(
                        event.toByte(),
                        (end or volume).toByte(),
                        (duration shr 8).toByte(), (duration and 0xff).toByte(),
                    )
                    val buf = rtpHeader + payload
                    if (!sendRtpPacket(sendCall.rtpSocket, buf, sendCall.rtpRemoteAddr, sendCall.rtpRemotePort, "RTP DTMF event=$event char=$c seq=$sequenceNumber ts=$timestamp duration=$duration end=${end != 0}")) return@thread
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
            lookTrackMatching(speechCodecRtpmapName(selectedAudioCodec), notAdditional = "octet-align=1") ?: return 488
        val (dtmfTrack, dtmfTrackDesc) =
            lookTrackMatching(telephoneEventRtpmapName(selectedAudioCodec)) ?: return 488
        val amrFmtpAnswer =
            trackRequirements(amrTrack) ?: defaultSpeechFmtpAnswer(amrTrack, selectedAudioCodec)
        val remotePtime = attributes.firstOrNull { it.startsWith("ptime:") } ?: "ptime:20"
        val remoteMaxptime = attributes.firstOrNull { it.startsWith("maxptime:") } ?: "maxptime:20"
        val allTracks = listOf(amrTrack, dtmfTrack)
        val sdpBandwidthAs = sdpBandwidthAsKbps(selectedAudioCodec)
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
            Rlog.w(TAG, "Rejecting duplicate incoming INVITE for recently terminated Call-ID: callId=$incomingCallId cseq=$incomingCseq")
            return 486
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

        val selectedAudioCodec = selectIncomingSpeechCodecFromOffer(
            sdp = sdp,
            context = "incoming INVITE callId=$incomingCallId",
        )

        val (amrTrack, amrTrackDesc) = lookTrackMatching(
            speechCodecRtpmapName(selectedAudioCodec),
            additional = "",
            notAdditional = "octet-align=1",
        ) ?: return 488
        val amrTrackRequirements = trackRequirements(amrTrack)
        val amrFmtpAnswer = amrTrackRequirements ?: defaultSpeechFmtpAnswer(amrTrack, selectedAudioCodec)

        val (dtmfTrack, dtmfTrackDesc) =
            lookTrackMatching(telephoneEventRtpmapName(selectedAudioCodec)) ?: return 488

        val allTracks = listOf(amrTrack, dtmfTrack)
        val sdpBandwidthAs = sdpBandwidthAsKbps(selectedAudioCodec)
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
            val dialogContact = "<sip:$owner@$local;transport=tcp>"
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
            val installedIncomingCallId = currentCall?.callIdOrEmpty().orEmpty()
            if (wasRecentlyTerminatedIncomingCall(incomingCallId) || installedIncomingCallId != incomingCallId) {
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
                mapOf("call-id" to incomingCallId) + audioCodecExtras(selectedAudioCodec),
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
                synchronized(incomingResponseWriter) { incomingResponseWriter.write(msg.toByteArray()) }
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
        smsHandler.sendSms(smsSmsc, pdu, ref, successCb, failCb)
    }

    fun sendSmsAck(token: Int, ref: Int, error: Boolean) {
        smsHandler.sendSmsAck(token, ref, error)
    }
}
