//SPDX-License-Identifier: GPL-2.0
package me.phh.ims

import android.content.Context
import android.os.Bundle
import android.os.Message
import android.telephony.Rlog
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.telephony.ims.ImsCallProfile
import android.telephony.ims.ImsCallSessionListener
import android.telephony.ims.ImsReasonInfo
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
class PhhMmTelFeature(val slotId: Int) : PhhMmTelFeatureProtected(slotId) {
    companion object {
        private const val TAG = "PHH MmTelFeature"
    }

    var telephonyManager: TelephonyManager? = null

    val imsSms = PhhImsSms(slotId)
    lateinit var sipHandler: SipHandler
    private var outgoingCallListener: ImsCallSessionListener? = null
    private var outgoingCallActive = false
    fun getSipHandlerOrNull(): SipHandler? = if (this::sipHandler.isInitialized) sipHandler else null

    private fun refreshMmTelCapabilities(reason: String) {
        val capabilities = MmTelCapabilities()
        capabilities.addCapabilities(
            MmTelCapabilities.CAPABILITY_TYPE_VOICE or
                MmTelCapabilities.CAPABILITY_TYPE_SMS
        )
        Rlog.d(TAG, "Refreshing MmTel capabilities after $reason: $capabilities")
        notifyCapabilitiesStatusChanged(capabilities)
    }

    override fun initialize(context: Context?, slotId: Int) {
        super.initialize(context, slotId)
        featureState = STATE_INITIALIZING

        telephonyManager =
            mContext.getSystemService(TelephonyManager::class.java)
                .createForSubscriptionId(SubscriptionManager.getSubscriptionId(slotId))

        telephonyManager?.registerTelephonyCallback(
            Executors.newSingleThreadExecutor(),
            object : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
                override fun onServiceStateChanged(serviceState: ServiceState) {
                    // STATE_IN_SERVICE requires SIM unlocked and fully registered.
                    // During PIN-lock phase state is STATE_EMERGENCY_ONLY — skip it.
                    if (serviceState.state != ServiceState.STATE_IN_SERVICE) return
                    serviceState.networkRegistrationInfoList.forEach {
                        // A valid RPLMN is needed for SipHandler
                        if (!(it.registeredPlmn?.isEmpty() ?: true)) {
                            featureState = STATE_READY
                            telephonyManager?.unregisterTelephonyCallback(this)
                        }
                    }
                }
            })
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
            sipHandler.onOutgoingCallConnected = { _: Object, _: Map<String, String> ->
                Rlog.d(TAG, "Outgoing call connected")
                session.mState = ImsCallSessionImplBase.State.ESTABLISHED
                val callProfile = makeVoiceCallProfile()
                session.mListener.callSessionInitiated(callProfile)
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

        return callProfile
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
        if(this::sipHandler.isInitialized) return

        // call onRegistering first then
        // register SIP here and call onRegistered after .. register.
        val imsService = PhhImsService.Companion.instance!!
        sipHandler = SipHandler(imsService)
        sipHandler.imsFailureCallback = {
            imsService.getRegistration(slotId).onDeregistered(null)
        }
        sipHandler.imsRegisteringCallback = { tech ->
            Rlog.d(TAG, "IMS SIP registering, reporting registration tech $tech")
            imsService.getRegistration(slotId).onRegistering(tech)
        }
        sipHandler.imsReadyCallback = {
            val tech = sipHandler.getRegistrationTech()
            Rlog.d(TAG, "IMS SIP registered, reporting registration tech $tech")
            imsService.getRegistration(slotId).onRegistered(tech)
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
                ImsStreamMediaProfile.AUDIO_QUALITY_EVS_FB,
            )
            notifyIncomingCall(object: ImsCallSessionImplBase() {
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

            }, Bundle())
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
        Rlog.d(TAG, "Should process call? ${numbers.toList()}")
        return PROCESS_CALL_IMS
    }
}
