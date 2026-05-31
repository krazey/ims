//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.media.MediaCodec
import android.telephony.Rlog

object SipAudioCodecNegotiator {
    fun isMediaCodecAvailableFor(
        logTag: String,
        audioCodec: NegotiatedAudioCodec,
    ): Boolean {
        var encoder: MediaCodec? = null
        var decoder: MediaCodec? = null
        return try {
            encoder = MediaCodec.createEncoderByType(audioCodec.mimeType)
            decoder = MediaCodec.createDecoderByType(audioCodec.mimeType)
            Rlog.d(logTag, "MediaCodec available for ${audioCodec.name}: mime=${audioCodec.mimeType}")
            true
        } catch (t: Throwable) {
            Rlog.w(logTag, "MediaCodec unavailable for ${audioCodec.name}: mime=${audioCodec.mimeType}", t)
            false
        } finally {
            try { encoder?.release() } catch (_: Throwable) { }
            try { decoder?.release() } catch (_: Throwable) { }
        }
    }

    fun speechCodecRtpmapName(audioCodec: NegotiatedAudioCodec): String =
        "${audioCodec.sdpCodecName}/${audioCodec.rtpClockRate}"

    fun telephoneEventRtpmapName(audioCodec: NegotiatedAudioCodec): String =
        "telephone-event/${audioCodec.rtpClockRate}"

    fun defaultSpeechFmtpAnswer(track: Int, audioCodec: NegotiatedAudioCodec): String =
        if (audioCodec == SipAudioCodecs.AMR_WB) {
            "fmtp:$track octet-align=0;mode-change-capability=2;max-red=0"
        } else {
            "fmtp:$track mode-set=7;octet-align=0;max-red=0"
        }

    fun sdpBandwidthAsKbps(audioCodec: NegotiatedAudioCodec): Int =
        if (audioCodec == SipAudioCodecs.AMR_WB) 88 else 38

    fun audioCodecExtras(audioCodec: NegotiatedAudioCodec): Map<String, String> =
        mapOf(
            "audio-codec" to audioCodec.name,
            "audio-codec-rate" to audioCodec.sampleRate.toString(),
        )

    fun selectIncomingSpeechCodecFromOffer(
        logTag: String,
        sdp: List<String>,
        context: String,
        amrWbMediaCodecAvailable: Boolean,
    ): NegotiatedAudioCodec {
        val candidates = SipAudioCodecSdpLogger.parseRemoteAudioCodecCandidates(sdp)
        val amrWbCandidate = SipAudioCodecSdpLogger.bestKnownWidebandCandidate(sdp)
        val amrNbCandidate = SipAudioCodecSdpLogger.bestCurrentlyImplementedCandidate(sdp)
        val hasAmrWbTelephoneEvent = candidates.any {
            it.codec == "TELEPHONE-EVENT" && it.rate == SipAudioCodecs.AMR_WB.rtpClockRate
        }

        val amrWbUsable =
            amrWbCandidate != null &&
            !amrWbCandidate.fmtp.contains("octet-align=1", ignoreCase = true) &&
            hasAmrWbTelephoneEvent

        if (amrWbUsable && amrWbMediaCodecAvailable) {
            Rlog.w(
                logTag,
                "$context selecting AMR-WB/16000 candidate=${SipAudioCodecSdpLogger.describeRemoteAudioCodecCandidate(amrWbCandidate!!)} " +
                    "mediaCodecAvailable=$amrWbMediaCodecAvailable " +
                    "hasTelephoneEvent16000=$hasAmrWbTelephoneEvent",
            )
            return SipAudioCodecs.AMR_WB
        }

        Rlog.d(
            logTag,
            "$context selecting AMR-NB/8000 fallback " +
                "amrWbCandidate=${amrWbCandidate?.let { SipAudioCodecSdpLogger.describeRemoteAudioCodecCandidate(it) }} " +
                "amrWbUsable=$amrWbUsable " +
                "mediaCodecAvailable=$amrWbMediaCodecAvailable " +
                "hasTelephoneEvent16000=$hasAmrWbTelephoneEvent " +
                "amrNbCandidate=${amrNbCandidate?.let { SipAudioCodecSdpLogger.describeRemoteAudioCodecCandidate(it) }}",
        )
        return SipAudioCodecs.AMR_NB
    }

    fun selectOutgoingSpeechCodecFromAnswer(
        logTag: String,
        sdp: List<String>,
        context: String,
        amrWbMediaCodecAvailable: Boolean,
    ): NegotiatedAudioCodec {
        val candidates = SipAudioCodecSdpLogger.parseRemoteAudioCodecCandidates(sdp)
        val amrWbCandidate = SipAudioCodecSdpLogger.bestKnownWidebandCandidate(sdp)
        val amrNbCandidate = SipAudioCodecSdpLogger.bestCurrentlyImplementedCandidate(sdp)
        val hasAmrWbTelephoneEvent = candidates.any {
            it.codec == "TELEPHONE-EVENT" && it.rate == SipAudioCodecs.AMR_WB.rtpClockRate
        }

        val amrWbUsable =
            amrWbCandidate != null &&
            !amrWbCandidate.fmtp.contains("octet-align=1", ignoreCase = true) &&
            hasAmrWbTelephoneEvent

        if (amrWbUsable && amrWbMediaCodecAvailable) {
            Rlog.w(
                logTag,
                "$context outgoing answer selected AMR-WB/16000 candidate=${SipAudioCodecSdpLogger.describeRemoteAudioCodecCandidate(amrWbCandidate!!)} " +
                    "mediaCodecAvailable=$amrWbMediaCodecAvailable " +
                    "hasTelephoneEvent16000=$hasAmrWbTelephoneEvent",
            )
            return SipAudioCodecs.AMR_WB
        }

        Rlog.d(
            logTag,
            "$context outgoing answer selected AMR-NB/8000 fallback " +
                "amrWbCandidate=${amrWbCandidate?.let { SipAudioCodecSdpLogger.describeRemoteAudioCodecCandidate(it) }} " +
                "amrWbUsable=$amrWbUsable " +
                "mediaCodecAvailable=$amrWbMediaCodecAvailable " +
                "hasTelephoneEvent16000=$hasAmrWbTelephoneEvent " +
                "amrNbCandidate=${amrNbCandidate?.let { SipAudioCodecSdpLogger.describeRemoteAudioCodecCandidate(it) }}",
        )
        return SipAudioCodecs.AMR_NB
    }
}
