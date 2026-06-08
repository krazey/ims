//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.Rlog

internal data class NegotiatedAudioCodec(
    val name: String,
    val sdpCodecName: String,
    val mimeType: String,
    val rtpClockRate: Int,
    val sampleRate: Int,
    val channelCount: Int,
    val bitRate: Int,
    val frameDurationMs: Int,
    val rtpTimestampStep: Int,
    val storageFrameSizeBytes: Int,
    val prefersOctetAlignedRtp: Boolean,
)

internal object SipAudioCodecs {
    // Current stable call path: AMR-NB, bandwidth-efficient RTP payload.
    // AMR-WB/EVS will add new codec profiles later, but should not change
    // this fallback profile's behavior.
    val AMR_NB = NegotiatedAudioCodec(
        name = "AMR-NB",
        sdpCodecName = "AMR",
        mimeType = "audio/3gpp",
        rtpClockRate = 8000,
        sampleRate = 8000,
        channelCount = 1,
        bitRate = 12200,
        frameDurationMs = 20,
        rtpTimestampStep = 160,
        storageFrameSizeBytes = 32,
        prefersOctetAlignedRtp = false,
    )

    // Future HD Voice target. This profile is intentionally not selected yet;
    // the current media path still uses AMR_NB until AMR-WB RTP payload
    // parsing/packetization is implemented and validated.
    val AMR_WB = NegotiatedAudioCodec(
        name = "AMR-WB",
        sdpCodecName = "AMR-WB",
        mimeType = "audio/amr-wb",
        rtpClockRate = 16000,
        sampleRate = 16000,
        channelCount = 1,
        bitRate = 12650,
        frameDurationMs = 20,
        rtpTimestampStep = 320,
        // Enough for one AMR-WB storage-format frame at the highest mode:
        // one frame header plus octet-padded speech bits.
        storageFrameSizeBytes = 61,
        prefersOctetAlignedRtp = false,
    )
}

internal data class RemoteAudioCodecCandidate(
    val payload: Int,
    val codec: String,
    val rate: Int?,
    val channels: String?,
    val fmtp: String,
    val offeredOrder: Int,
)

internal object SipAudioCodecSdpLogger {
    fun parseRemoteAudioCodecCandidates(sdp: List<String>): List<RemoteAudioCodecCandidate> {
        val mediaPayloads = sdp.firstOrNull { it.startsWith("m=audio ") }
            ?.split("\\s+".toRegex())
            ?.drop(3)
            ?.mapNotNull { it.toIntOrNull() }
            .orEmpty()
        val offeredOrder = mediaPayloads.withIndex().associate { it.value to it.index }

        val attributes = sdp.mapNotNull { line ->
            when {
                line.startsWith("a=") -> line.substring(2)
                line.startsWith("rtpmap:", ignoreCase = true) -> line
                line.startsWith("fmtp:", ignoreCase = true) -> line
                else -> null
            }
        }
        val fmtpByPayload = attributes
            .filter { it.startsWith("fmtp:", ignoreCase = true) }
            .mapNotNull { fmtp ->
                val payload = fmtp.substringAfter("fmtp:")
                    .substringBefore(" ")
                    .substringBefore("\t")
                    .toIntOrNull()
                    ?: return@mapNotNull null
                payload to fmtp
            }
            .toMap()

        val rtpmapRegex = "^rtpmap:(\\d+)\\s+([^/\\s]+)/(\\d+)(?:/([^\\s]+))?.*".toRegex(
            RegexOption.IGNORE_CASE,
        )

        return attributes
            .filter { it.startsWith("rtpmap:", ignoreCase = true) }
            .mapNotNull { rtpmap ->
                val match = rtpmapRegex.matchEntire(rtpmap) ?: return@mapNotNull null
                val payload = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val codec = match.groupValues[2].uppercase()
                val rate = match.groupValues[3].toIntOrNull()
                val channels = match.groupValues.getOrNull(4)?.ifBlank { null }
                RemoteAudioCodecCandidate(
                    payload = payload,
                    codec = codec,
                    rate = rate,
                    channels = channels,
                    fmtp = fmtpByPayload[payload].orEmpty(),
                    offeredOrder = offeredOrder[payload] ?: Int.MAX_VALUE,
                )
            }
            .sortedBy { it.offeredOrder }
    }

    fun remoteAudioCodecCandidateRank(candidate: RemoteAudioCodecCandidate): Int {
        val fmtp = candidate.fmtp
        val octetAligned = fmtp.contains("octet-align=1", ignoreCase = true)
        return when {
            candidate.codec == "EVS" -> 600 + ((candidate.rate ?: 0) / 1000)
            candidate.codec == "AMR-WB" && candidate.rate == 16000 && octetAligned -> 520
            candidate.codec == "AMR-WB" && candidate.rate == 16000 -> 500
            candidate.codec == "AMR" && candidate.rate == 8000 && !octetAligned -> 300
            candidate.codec == "AMR" && candidate.rate == 8000 -> 250
            candidate.codec == "PCMA" && candidate.rate == 8000 -> 210
            candidate.codec == "PCMU" && candidate.rate == 8000 -> 200
            candidate.codec == "TELEPHONE-EVENT" -> 0
            else -> 50
        }
    }

    fun describeRemoteAudioCodecCandidate(candidate: RemoteAudioCodecCandidate): String {
        val flags = mutableListOf<String>()
        if (candidate.fmtp.contains("octet-align=1", ignoreCase = true)) {
            flags += "octet-align"
        }
        if (candidate.codec == "AMR" && candidate.rate == 8000 &&
            !candidate.fmtp.contains("octet-align=1", ignoreCase = true)
        ) {
            flags += "implemented-now"
        }
        return buildString {
            append(candidate.payload)
            append(" ")
            append(candidate.codec)
            append("/")
            append(candidate.rate ?: -1)
            candidate.channels?.let {
                append("/")
                append(it)
            }
            append(" order=")
            append(candidate.offeredOrder)
            append(" rank=")
            append(remoteAudioCodecCandidateRank(candidate))
            if (flags.isNotEmpty()) {
                append(" flags=")
                append(flags.joinToString(","))
            }
            if (candidate.fmtp.isNotBlank()) {
                append(" fmtp={")
                append(candidate.fmtp)
                append("}")
            }
        }
    }

    fun bestKnownWidebandCandidate(sdp: List<String>): RemoteAudioCodecCandidate? =
        parseRemoteAudioCodecCandidates(sdp)
            .filter {
                it.codec == SipAudioCodecs.AMR_WB.sdpCodecName &&
                    it.rate == SipAudioCodecs.AMR_WB.rtpClockRate
            }
            .minByOrNull { it.offeredOrder }

    fun bestCurrentlyImplementedCandidate(sdp: List<String>): RemoteAudioCodecCandidate? =
        parseRemoteAudioCodecCandidates(sdp)
            .filter {
                it.codec == SipAudioCodecs.AMR_NB.sdpCodecName &&
                    it.rate == SipAudioCodecs.AMR_NB.rtpClockRate &&
                    !it.fmtp.contains("octet-align=1", ignoreCase = true)
            }
            .minByOrNull { it.offeredOrder }

    fun logRemoteAudioCodecCandidates(tag: String, context: String, sdp: List<String>) {
        val candidates = parseRemoteAudioCodecCandidates(sdp)
        if (candidates.isEmpty()) {
            Rlog.w(tag, "$context remote audio codec candidates: none parsed from SDP")
            return
        }

        val implementedNowPayloads = candidates
            .filter {
                it.codec == "AMR" &&
                    it.rate == 8000 &&
                    !it.fmtp.contains("octet-align=1", ignoreCase = true)
            }
            .map { it.payload }

        val futureRanked = candidates
            .sortedWith(
                compareByDescending<RemoteAudioCodecCandidate> {
                    remoteAudioCodecCandidateRank(it)
                }.thenBy { it.offeredOrder },
            )
            .joinToString(" | ") { describeRemoteAudioCodecCandidate(it) }

        val bestWideband = bestKnownWidebandCandidate(sdp)
        val bestImplemented = bestCurrentlyImplementedCandidate(sdp)

        Rlog.d(
            tag,
            "$context remote audio codec candidates futureRanked=$futureRanked " +
                "implementedNowPayloads=$implementedNowPayloads " +
                "bestWideband=${bestWideband?.let { describeRemoteAudioCodecCandidate(it) }} " +
                "bestImplemented=${bestImplemented?.let { describeRemoteAudioCodecCandidate(it) }}",
        )
    }
}
