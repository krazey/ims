//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.telephony.Rlog

object SipAudioRecordRouting {
    fun pinBuiltinMic(
        logTag: String,
        context: Context,
        audioRecord: AudioRecord,
    ): AudioManager {
        // Pin capture to the built-in mic so the Samsung HAL cannot reroute it to
        // the baseband PCM path (pcmC0D110c) that produces silence for software IMS.
        // setPreferredDevice overrides HAL source-based routing while keeping
        // VOICE_COMMUNICATION semantics (call-mode output path stays correct).
        val audioManager = context.getSystemService(AudioManager::class.java)
        val builtinMic = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        if (builtinMic != null) {
            audioRecord.preferredDevice = builtinMic
            Rlog.d(logTag, "AudioRecord preferredDevice set to builtin mic: id=${builtinMic.id} name=${builtinMic.productName}")
        } else {
            Rlog.w(logTag, "AudioRecord: no TYPE_BUILTIN_MIC found, proceeding without preferredDevice")
        }
        return audioManager
    }
}
