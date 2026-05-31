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
}
