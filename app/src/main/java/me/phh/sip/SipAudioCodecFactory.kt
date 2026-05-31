//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.media.MediaCodec
import android.media.MediaFormat

object SipAudioCodecFactory {
    fun createStartedDecoder(
        audioCodec: NegotiatedAudioCodec,
    ): MediaCodec {
        val decoder = MediaCodec.createDecoderByType(audioCodec.mimeType)
        val mediaFormat = MediaFormat.createAudioFormat(
            audioCodec.mimeType,
            audioCodec.sampleRate,
            audioCodec.channelCount,
        )
        decoder.configure(mediaFormat, null, null, 0)
        decoder.start()
        return decoder
    }

    fun createStartedEncoder(
        audioCodec: NegotiatedAudioCodec,
    ): MediaCodec {
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
        return encoder
    }
}
