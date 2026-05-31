//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.telephony.Rlog

object SipAudioRecordStarter {
    fun startForImsUplink(
        logTag: String,
        audioManager: AudioManager,
        audioRecord: AudioRecord,
        encoder: MediaCodec,
    ): Int? {
        val previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        try {
            audioRecord.startRecording()
        } catch (t: Throwable) {
            Rlog.e(logTag, "AudioRecord.startRecording failed", t)
            try {
                audioRecord.release()
            } catch (_: Throwable) {
            }
            try {
                encoder.stop()
            } catch (_: Throwable) {
            }
            try {
                encoder.release()
            } catch (_: Throwable) {
            }
            return null
        }
        Rlog.d(
            logTag,
            "AudioRecord started, state=${audioRecord.recordingState} " +
                "audioMode=${audioManager.mode} (was $previousAudioMode) " +
                "preferredDevice=${audioRecord.preferredDevice?.type}"
        )
        return previousAudioMode
    }
}
