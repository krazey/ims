//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

object SipAudioTrackFactory {
    fun createVoiceCallTrack(
        audioCodec: NegotiatedAudioCodec = SipAudioCodecs.AMR_NB,
    ): AudioTrack {
        val minBufferSize = AudioTrack.getMinBufferSize(
            audioCodec.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )

        return AudioTrack(
            AudioManager.STREAM_VOICE_CALL,
            audioCodec.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize,
            AudioTrack.MODE_STREAM,
        )
    }
}
