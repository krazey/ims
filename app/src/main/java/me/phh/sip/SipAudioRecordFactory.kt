//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

object SipAudioRecordFactory {
    fun createVoiceCommunicationRecord(
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
    fun minBufferSize(
        audioCodec: NegotiatedAudioCodec = SipAudioCodecs.AMR_NB,
    ): Int =
        AudioRecord.getMinBufferSize(
            audioCodec.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
}
