//SPDX-License-Identifier: GPL-2.0
package me.phh.ims

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.telephony.Rlog
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.telephony.ims.ImsCallProfile
import android.telephony.ims.ImsCallSessionListener
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
import android.telephony.ims.ImsStreamMediaProfile
import android.telephony.ims.feature.ImsFeature
import android.telephony.ims.stub.ImsCallSessionImplBase
import android.telephony.ims.stub.ImsMultiEndpointImplBase
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE
import android.telephony.ims.stub.ImsSmsImplBase
import android.telephony.ims.stub.ImsUtImplBase
import java.util.concurrent.Executors
import java.lang.Object
import me.phh.sip.SipHandler
import me.phh.sip.randomBytes
import me.phh.sip.toHex

// frameworks/base/telephony/java/android/telephony/ims/feature/MmTelFeature.java
// We extend it through java once because kotlin cannot override
// changeEnabledCapabilities that has a protected (CapabilityCallbackProxy)
// argument. See this stackoverflow link for why we cannot do it directly:
// https://stackoverflow.com/questions/49284094/inheritance-from-java-class-with-a-public-method-accepting-a-protected-class-in/49287402#49287402
class PhhMmTelFeature(
    val slotId: Int,
    initialSubId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
) : PhhMmTelFeatureProtected(slotId) {
    companion object {
        private const val TAG = "PHH MmTelFeature"
    }

    var telephonyManager: TelephonyManager? = null
    private val readyCheckHandler = Handler(Looper.getMainLooper())
    private val readyCheckExecutor = Executors.newSingleThreadExecutor()
    private var readyCheckCallback: TelephonyCallback? = null
    private var readyCheckAttempts = 0
    private var frameworkSubId = initialSubId
    private var featureInitialized = false

    val imsSms = PhhImsSms(slotId)
    lateinit var sipHandler: SipHandler
    private var sipHandlerSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    private var outgoingCallListener: ImsCallSessionListener? = null
    private var outgoingCallActive = false
    fun getSipHandlerOrNull(): SipHandler? {
        return if (
            this::sipHandler.isInitialized &&
            SubscriptionManager.isValidSubscriptionId(sipHandlerSubId)
        ) {
            sipHandler
        } else {
            null
        }
    }

    private fun refreshMmTelCapabilities(reason: String) {
        val capabilities = MmTelCapabilities()
        capabilities.addCapabilities(
            MmTelCapabilities.CAPABILITY_TYPE_VOICE or
                MmTelCapabilities.CAPABILITY_TYPE_SMS
        )
        Rlog.d(TAG, "Refreshing MmTel capabilities after $reason: $capabilities")
        notifyCapabilitiesStatusChanged(capabilities)
    }

    private fun resolveSubIdForSlot(): Int {
        val subscriptionManager = mContext.getSystemService(SubscriptionManager::class.java)
        val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList.orEmpty()

        if (SubscriptionManager.isValidSubscriptionId(frameworkSubId)) {
            val frameworkSlot = activeSubscriptions
                .firstOrNull { it.subscriptionId == frameworkSubId }
                ?.simSlotIndex

            if (frameworkSlot == slotId) {
                return frameworkSubId
            }

            if (frameworkSlot == null && activeSubscriptions.isEmpty()) {
                return frameworkSubId
            }

            Rlog.w(
                TAG,
                "$slotId ignoring frameworkSubId=$frameworkSubId because active slot is $frameworkSlot",
            )
        }

        val activeSubId = activeSubscriptions
            .firstOrNull { it.simSlotIndex == slotId }
            ?.subscriptionId

        if (activeSubId != null && SubscriptionManager.isValidSubscriptionId(activeSubId)) {
            return activeSubId
        }

        val frameworkSlotSubId = SubscriptionManager.getSubscriptionId(slotId)
        if (SubscriptionManager.isValidSubscriptionId(frameworkSlotSubId)) {
            return frameworkSlotSubId
        }

        return SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    private fun markReadyFromServiceState(serviceState: ServiceState, reason: String): Boolean {
        val registeredPlmn = serviceState.networkRegistrationInfoList
            .firstOrNull { !it.registeredPlmn.isNullOrEmpty() }
            ?.registeredPlmn

        if (serviceState.state != ServiceState.STATE_IN_SERVICE || registeredPlmn == null) {
            Rlog.d(
                TAG,
                "$slotId not ready for IMS after $reason: " +
                    "state=${serviceState.state} registeredPlmn=$registeredPlmn"
            )
            return false
        }

        if (featureState != STATE_READY) {
            Rlog.d(
                TAG,
                "$slotId ready for IMS after $reason: " +
                    "subId=${resolveSubIdForSlot()} registeredPlmn=$registeredPlmn"
            )
            featureState = STATE_READY
        }

        return true
    }

    private fun retireSipHandler(reason: String) {
        if (
            this::sipHandler.isInitialized &&
            SubscriptionManager.isValidSubscriptionId(sipHandlerSubId)
        ) {
            Rlog.w(
                TAG,
                "$slotId retiring SipHandler for subId=$sipHandlerSubId: $reason",
            )
            sipHandler.shutdown(reason, notifyFramework = false)
        }

        sipHandlerSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    private fun unregisterReadyCheckCallback(reason: String) {
        readyCheckCallback?.let { oldCallback ->
            try {
                telephonyManager?.unregisterTelephonyCallback(oldCallback)
            } catch (t: Throwable) {
                Rlog.d(TAG, "$slotId unregister IMS ready callback failed after $reason: ${t.message}")
            }
            readyCheckCallback = null
        }
    }

    private fun bindReadyCheckTelephonyManager(reason: String) {
        val subId = resolveSubIdForSlot()
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            if (readyCheckAttempts < 30) {
                readyCheckAttempts++
                Rlog.w(
                    TAG,
                    "$slotId no valid subId for IMS ready check after $reason; " +
                        "retry $readyCheckAttempts"
                )
                readyCheckHandler.postDelayed({
                    bindReadyCheckTelephonyManager("subId retry")
                }, 1000L)
            } else {
                Rlog.w(TAG, "$slotId giving up IMS ready check: no valid subId")
            }
            return
        }

        readyCheckAttempts = 0

        unregisterReadyCheckCallback("rebinding IMS ready check")

        val boundTelephonyManager = mContext
            .getSystemService(TelephonyManager::class.java)
            .createForSubscriptionId(subId)

        telephonyManager = boundTelephonyManager

        Rlog.d(TAG, "$slotId binding IMS ready check to subId=$subId after $reason")

        val callback = object : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
            override fun onServiceStateChanged(serviceState: ServiceState) {
                if (markReadyFromServiceState(serviceState, "service state callback")) {
                    try {
                        boundTelephonyManager.unregisterTelephonyCallback(this)
                    } catch (t: Throwable) {
                        Rlog.d(TAG, "$slotId unregister IMS ready callback failed: ${t.message}")
                    }
                    if (readyCheckCallback === this) {
                        readyCheckCallback = null
                    }
                }
            }
        }

        readyCheckCallback = callback
        boundTelephonyManager.registerTelephonyCallback(readyCheckExecutor, callback)

        boundTelephonyManager.serviceState?.let { currentState ->
            if (markReadyFromServiceState(currentState, "current service state")) {
                try {
                    boundTelephonyManager.unregisterTelephonyCallback(callback)
                } catch (t: Throwable) {
                    Rlog.d(TAG, "$slotId unregister IMS ready callback failed: ${t.message}")
                }
                if (readyCheckCallback === callback) {
                    readyCheckCallback = null
                }
            }
        }
    }

    fun onSubscriptionChangedFromService(subscriptionId: Int) {
        val oldFrameworkSubId = frameworkSubId
        frameworkSubId = subscriptionId

        Rlog.d(
            TAG,
            "$slotId service subscription update oldFrameworkSubId=$oldFrameworkSubId " +
                "newSubId=$subscriptionId handlerSubId=$sipHandlerSubId",
        )

        if (!featureInitialized) {
            Rlog.d(TAG, "$slotId deferring service subscription update until initialize")
            return
        }

        if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
            unregisterReadyCheckCallback("service subscription invalid")
            retireSipHandler("subscription removed oldSubId=$sipHandlerSubId")
            featureState = STATE_INITIALIZING
            return
        }

        if (
            this::sipHandler.isInitialized &&
            SubscriptionManager.isValidSubscriptionId(sipHandlerSubId) &&
            sipHandlerSubId != subscriptionId
        ) {
            retireSipHandler(
                "service subscription changed oldSubId=$sipHandlerSubId newSubId=$subscriptionId"
            )
            featureState = STATE_INITIALIZING
        }

        if (featureState != STATE_READY) {
            bindReadyCheckTelephonyManager("service subscription update")
        }
    }

    override fun initialize(context: Context?, slotId: Int) {
        super.initialize(context, slotId)

        featureInitialized = true
        featureState = STATE_INITIALIZING
        bindReadyCheckTelephonyManager("initialize")
    }

    override fun createCallProfile(callSessionType: Int, callType: Int): ImsCallProfile {
        Rlog.d(TAG, "$slotId createCallProfile $callSessionType $callType")
        // check why not called
        // figure out RilHolder.INSTANCE.getRadios(mSlotId).setImsCfg ? Probably only required
        // if we leave ims to the radio...
        return ImsCallProfile(callSessionType, callType)
    }
    override fun createCallSession(profile: ImsCallProfile): ImsCallSessionImplBase {
        Rlog.d(TAG, "$slotId createCallSession")
        return object: ImsCallSessionImplBase() {
            private val mCallId = randomBytes(12).toHex()
            lateinit var mListener: ImsCallSessionListener
            var mState = State.INITIATED
            var currentCallProfile: ImsCallProfile = applyCallNetworkType(profile, "session initial profile")

            override fun getCallProfile(): ImsCallProfile {
                return currentCallProfile
            }

            override fun getLocalCallProfile(): ImsCallProfile {
                return currentCallProfile
            }

            override fun getRemoteCallProfile(): ImsCallProfile {
                return currentCallProfile
            }
            override fun getCallId(): String {
                return mCallId
            }

            override fun close() {
                Rlog.d(TAG, "Closing call")
            }

            override fun accept(callType: Int, profile: ImsStreamMediaProfile) {
                Rlog.d(TAG, "Accepting call with callType $callType profile $profile")
            }

            override fun isInCall(): Boolean {
                return true
            }

            override fun start(callee: String, profile: ImsCallProfile) {
            Rlog.d(TAG, "Starting call with $callee profile $profile")

            if (!sipHandler.isReadyForOutgoingCall()) {
                Rlog.w(TAG, "Rejecting outgoing call while IMS is reconnecting/not ready")
                mState = State.TERMINATED

                if (this::mListener.isInitialized) {
                    mListener.callSessionTerminated(
                        ImsReasonInfo(
                            ImsReasonInfo.CODE_NETWORK_REJECT,
                            0,
                            "IMS reconnecting",
                        )
                    )
                } else {
                    Rlog.w(TAG, "No listener set while rejecting outgoing call during IMS reconnect")
                }

                return
            }

            outgoingCallActive = true
            sipHandler.call(callee)
        }

            override fun getState(): Int {
                return mState
            }

            override fun setListener(listener: ImsCallSessionListener) {
                Rlog.d(TAG, "Setting CallListener to $listener")
                mListener = listener
                outgoingCallListener = listener
            }

            override fun reject(reason: Int) {
                Rlog.d(TAG, "Rejecting call with reason $reason")
            }

            override fun sendDtmf(c: Char, result: Message?) {
                Rlog.d(TAG, "Sending outgoing DTMF $c")
                sipHandler.sendDtmf(c)
                result?.sendToTarget()
            }
            override fun startDtmf(c: Char) {
                Rlog.d(TAG, "Starting outgoing DTMF $c")
                sipHandler.sendDtmf(c)
            }
            override fun stopDtmf() {
                Rlog.d(TAG, "Stopping outgoing DTMF")
            }
            override fun terminate(reason: Int) {
                Rlog.d(TAG, "Terminating call with reason $reason")
                sipHandler.myHandler.post {
                    sipHandler.terminateCall()
                    mListener.callSessionTerminated(ImsReasonInfo(ImsReasonInfo.CODE_USER_TERMINATED, 0, "Kikoo"))
                    outgoingCallActive = false
                    if (outgoingCallListener == mListener) {
                        outgoingCallListener = null
                    }
                }
            }
        }.also { session ->
            sipHandler.onOutgoingCallConnected = { _: Object, extras: Map<String, String> ->
                Rlog.d(TAG, "Outgoing call connected")
                session.mState = ImsCallSessionImplBase.State.ESTABLISHED
                val callProfile = makeVoiceCallProfile(
                    audioQuality = audioQualityFromSipExtras(extras),
                )
                session.currentCallProfile = callProfile
                session.mListener.callSessionInitiated(callProfile)
            }

            sipHandler.onOutgoingCallProgressing = { _: Object, extras: Map<String, String> ->
                Rlog.d(TAG, "Outgoing call progressing: $extras")
                val callProfile = makeVoiceCallProfile()
                callProfile.mMediaProfile.mAudioDirection =
                    android.telephony.ims.ImsStreamMediaProfile.DIRECTION_INACTIVE
                session.currentCallProfile = callProfile
                session.mListener.callSessionProgressing(callProfile.mMediaProfile)
            }

        }
    }

    fun getInstance(slotId: Int): PhhMmTelFeature {
        Rlog.d(TAG, "$slotId getInstance")
        return PhhMmTelFeature(slotId)
    }

    override fun getMultiEndpoint(): ImsMultiEndpointImplBase {
        Rlog.d(TAG, "$slotId getMultiEndpoint")
        return ImsMultiEndpointImplBase()
    }

    override fun getSmsImplementation(): ImsSmsImplBase {
        Rlog.d(TAG, "$slotId getSmsImplementation")
        return imsSms
    }

    override fun getUt(): ImsUtImplBase {
        Rlog.d(TAG, "$slotId getUt")
        return ImsUtImplBase()
    }

    private fun makeVoiceCallProfile(
        callerNumber: String? = null,
        audioQuality: Int = ImsStreamMediaProfile.AUDIO_QUALITY_AMR,
    ): ImsCallProfile {
        val callProfile = ImsCallProfile(
            ImsCallProfile.SERVICE_TYPE_NORMAL,
            ImsCallProfile.CALL_TYPE_VOICE,
            Bundle(),
            ImsStreamMediaProfile(
                audioQuality,
                ImsStreamMediaProfile.DIRECTION_SEND_RECEIVE,
                ImsStreamMediaProfile.VIDEO_QUALITY_NONE,
                ImsStreamMediaProfile.DIRECTION_INACTIVE,
                ImsStreamMediaProfile.RTT_MODE_DISABLED,
            ),
        )

        val normalizedCaller = callerNumber?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedCaller != null) {
            callProfile.setCallExtra(ImsCallProfile.EXTRA_OI, normalizedCaller)
            callProfile.setCallExtra(ImsCallProfile.EXTRA_CNA, normalizedCaller)
            callProfile.setCallExtra(ImsCallProfile.EXTRA_DISPLAY_TEXT, normalizedCaller)
            callProfile.setCallExtraInt(
                ImsCallProfile.EXTRA_OIR,
                ImsCallProfile.OIR_PRESENTATION_NOT_RESTRICTED,
            )
            callProfile.setCallExtraInt(
                ImsCallProfile.EXTRA_CNAP,
                ImsCallProfile.OIR_PRESENTATION_NOT_RESTRICTED,
            )
        }

        return applyCallNetworkType(callProfile, "voice profile")
    }

    private fun currentImsCallNetworkType(): Int {
        val registrationTech = getSipHandlerOrNull()?.getRegistrationTech()
        return when (registrationTech) {
            REGISTRATION_TECH_IWLAN -> TelephonyManager.NETWORK_TYPE_IWLAN
            REGISTRATION_TECH_LTE -> TelephonyManager.NETWORK_TYPE_LTE
            else -> TelephonyManager.NETWORK_TYPE_UNKNOWN
        }
    }

    private fun applyCallNetworkType(callProfile: ImsCallProfile, reason: String): ImsCallProfile {
        val registrationTech = getSipHandlerOrNull()?.getRegistrationTech()
        val networkType = currentImsCallNetworkType()
        callProfile.setCallExtraInt(ImsCallProfile.EXTRA_CALL_NETWORK_TYPE, networkType)
        Rlog.d(
            TAG,
            "Applying IMS call network type: reason=$reason " +
                "registrationTech=$registrationTech networkType=$networkType",
        )
        return callProfile
    }

    private fun audioQualityFromSipExtras(extras: Map<*, *>): Int {
        val codec = extras["audio-codec"] as? String
        val rate = (extras["audio-codec-rate"] as? String)?.toIntOrNull()
        val quality = when (codec) {
            "AMR-WB" -> ImsStreamMediaProfile.AUDIO_QUALITY_AMR_WB
            else -> ImsStreamMediaProfile.AUDIO_QUALITY_AMR
        }

        Rlog.d(
            TAG,
            "Mapping SIP codec to framework audio quality: " +
                "codec=$codec rate=$rate quality=$quality",
        )
        return quality
    }

    private fun cancelledReasonInfo(map: Map<*, *>): ImsReasonInfo {
        val statusCode = (map["statusCode"] as? String)?.toIntOrNull() ?: -1
        val statusMessage = (map["statusString"] as? String) ?: "Kikoo"
        val localReject = map["localReject"] == "true"
        val remoteNoMediaRelease = map["remoteNoMediaRelease"] == "true"

        return when {
            localReject -> ImsReasonInfo(ImsReasonInfo.CODE_USER_DECLINE, 0, statusMessage)

            remoteNoMediaRelease -> {
                Rlog.w(TAG, "No-media outgoing release; reporting as remote termination for Dialer UX: $statusMessage")
                ImsReasonInfo(ImsReasonInfo.CODE_USER_TERMINATED_BY_REMOTE, 0, statusMessage)
            }

            statusCode >= 400 -> ImsReasonInfo(ImsReasonInfo.CODE_NETWORK_REJECT, 0, statusMessage)

            else -> ImsReasonInfo(ImsReasonInfo.CODE_USER_TERMINATED_BY_REMOTE, 0, "Kikoo")
        }
    }

    override fun onFeatureReady() {
        Rlog.d(TAG, "$slotId onFeatureReady")

        val subId = resolveSubIdForSlot()
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            Rlog.w(TAG, "$slotId onFeatureReady without valid subId; retiring stale handler")
            unregisterReadyCheckCallback("onFeatureReady without valid subId")
            retireSipHandler("onFeatureReady without valid subId")
            featureState = STATE_INITIALIZING
            bindReadyCheckTelephonyManager("onFeatureReady without valid subId")
            return
        }

        if (
            this::sipHandler.isInitialized &&
            SubscriptionManager.isValidSubscriptionId(sipHandlerSubId)
        ) {
            if (sipHandlerSubId == subId && sipHandler.handlesSubscription(subId)) {
                return
            }

            Rlog.w(
                TAG,
                "$slotId subscription changed for existing SipHandler: " +
                    "oldSubId=$sipHandlerSubId newSubId=$subId; replacing"
            )
            retireSipHandler(
                "subscription changed oldSubId=$sipHandlerSubId newSubId=$subId"
            )
        }

        // call onRegistering first then
        // register SIP here and call onRegistered after .. register.
        val imsService = PhhImsService.Companion.instance!!
        sipHandlerSubId = subId
        sipHandler = SipHandler(imsService, slotId, subId)
sipHandler.imsFailureCallback = {
            imsService.getRegistrationForSubscription(slotId, subId).onDeregistered(null)
        }
        sipHandler.imsRegisteringCallback = { tech ->
            Rlog.d(TAG, "IMS SIP registering, reporting registration tech $tech")
            imsService.getRegistrationForSubscription(slotId, subId).onRegistering(tech)
        }
        sipHandler.imsReadyCallback = {
            val tech = sipHandler.getRegistrationTech()
            Rlog.d(TAG, "IMS SIP registered, reporting registration tech $tech")
            imsService.getRegistrationForSubscription(slotId, subId).onRegistered(tech)
            refreshMmTelCapabilities("SIP registered")
        }
        imsSms.sipHandler = sipHandler
        sipHandler.onSmsReceived = imsSms::onSmsReceived
        sipHandler.onSmsStatusReportReceived = imsSms::onSmsStatusReportReceived

        var callListener: ImsCallSessionListener? = null
        sipHandler.onIncomingCall = { handle: Object, from: String, extras: Map<String, String> -> 
            val callerNumber = from.trim()
            val callProfile = makeVoiceCallProfile(
            callerNumber,
            audioQualityFromSipExtras(extras),
        )
            val incomingSession = object: ImsCallSessionImplBase() {
                var mState = State.IDLE
                override fun getCallProfile(): ImsCallProfile {
                    return callProfile
                }
                override fun setListener(listener: ImsCallSessionListener) {
                    Rlog.d(TAG, "Setting CallListener to $listener")
                    callListener = listener
                }

                override fun getCallId(): String {
                    return extras["call-id"]!!
                }

                override fun getLocalCallProfile(): ImsCallProfile {
                    return callProfile
                }
                override fun getRemoteCallProfile(): ImsCallProfile {
                    return callProfile
                }
                override fun getProperty(name: String): String {
                    Rlog.d(TAG, "ImsCallSession.getProperty " + name)
                    return ""
                }

                override fun getState(): Int {
                    return mState
                }

                override fun start(callee: String, profile: ImsCallProfile) {
                    Rlog.d(TAG, "Starting call with $callee")
                }

                override fun accept(callType: Int, profile: ImsStreamMediaProfile) {
                    Rlog.d(TAG, "Accepting call with profile $profile")
                    sipHandler.acceptCall()
                    mState = State.ESTABLISHED
                    callListener?.callSessionInitiated(callProfile)
                }

                override fun deflect(deflectNumber: String?) {
                    Rlog.d(TAG, "Deflecting call to $deflectNumber")
                }

                override fun reject(reason: Int) {
                    sipHandler.rejectCall()
                    Rlog.d(TAG, "Rejecting call $reason")
                }

                override fun sendDtmf(c: Char, result: Message?) {
                    Rlog.d(TAG, "Sending incoming-call DTMF $c")
                    sipHandler.sendDtmf(c)
                    result?.sendToTarget()
                }

                override fun startDtmf(c: Char) {
                    Rlog.d(TAG, "Starting incoming-call DTMF $c")
                    sipHandler.sendDtmf(c)
                }

                override fun stopDtmf() {
                    Rlog.d(TAG, "Stopping incoming-call DTMF")
                }

                override fun terminate(reason: Int) {
                    Rlog.d(TAG, "Terminating call")
                    sipHandler.myHandler.post {
                        sipHandler.terminateCall()
                        callListener?.callSessionTerminated(ImsReasonInfo(ImsReasonInfo.CODE_USER_TERMINATED, 0, "Kikoo"))
                    }
                }

            }
            val frameworkCallListener = notifyIncomingCall(incomingSession, incomingSession.getCallId(), Bundle())
            if (frameworkCallListener != null) {
                incomingSession.setListener(frameworkCallListener)
            } else {
                Rlog.w(TAG, "Framework rejected incoming IMS call ${incomingSession.getCallId()}")
            }
        }
        sipHandler.onCancelledCall = { param: Object, reason: String, map: Map<String, String> ->
    Rlog.d(TAG, "Cancelling call")
        val reasonInfo = cancelledReasonInfo(map)
            if (outgoingCallActive) {
        outgoingCallListener?.callSessionTerminated(reasonInfo)
        outgoingCallActive = false
        outgoingCallListener = null
    } else {
        callListener?.callSessionTerminated(reasonInfo)
    }
}
        sipHandler.getVolteNetwork()
    }

    override fun onFeatureRemoved() {
        Rlog.d(TAG, "$slotId onFeatureRemoved")

        unregisterReadyCheckCallback("feature removed")
        retireSipHandler("feature removed")

        frameworkSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        featureInitialized = false
        featureState = STATE_INITIALIZING
    }

    // ints are @MmTelCapabilities.MmTelCapability and @ImsRegistrationImplBase.ImsRegistrationTech
    override fun queryCapabilityConfiguration(capability: Int, radioTech: Int): Boolean {
        Rlog.d(TAG, "$slotId queryCapabilityConfiguration $capability $radioTech")
        return capability == MmTelCapabilities.CAPABILITY_TYPE_SMS || capability == MmTelCapabilities.CAPABILITY_TYPE_VOICE
    }

    override fun setUiTtyMode(mode: Int, onCompleteMessage: Message?) {
        Rlog.d(TAG, "$slotId setUiTtyMode $onCompleteMessage")
    }

    override fun shouldProcessCall(numbers: Array<out String>): Int {
        Rlog.d(TAG, "Should process call? ${numbers.contentToString()}")

        val csfbNumber = numbers.firstOrNull { number ->
            this::sipHandler.isInitialized &&
                sipHandler.shouldForceCsfbForOutgoingDialString(number)
        }
        if (csfbNumber != null) {
            Rlog.w(
                TAG,
                "Forcing CSFB for outgoing MMI/service-code-like dial target: $csfbNumber",
            )
            return 1 /* PROCESS_CALL_CSFB */
        }
        return 0 /* PROCESS_CALL_IMS */
    }
}
