// SPDX-License-Identifier: GPL-2.0-or-later
package com.mrjackspade.kairodos

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.mrjackspade.kairo.frontend.AudioTrackBufferPolicy

/** Real Android output gate: negotiated size, sustained writes, and forced underrun recovery. */
internal object AudioOutputPolicyFixture {
    fun verify(): String {
        val rate = 48000
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum, 16384))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        try {
            val original = track.bufferSizeInFrames
            val policy = AudioTrackBufferPolicy(track)
            val reduced = track.bufferSizeInFrames
            check(reduced in 1..original)
            val samples = ShortArray(2048)
            track.play()
            val start = System.nanoTime()
            while (System.nanoTime() - start < 1_500_000_000L) {
                check(track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING) == samples.size)
                policy.checkAfterWrite()
            }
            val before = track.underrunCount
            check(track.bufferSizeInFrames == reduced) { "Healthy output unexpectedly fell back" }
            // Deliberately starve output long enough to exceed both application and HAL queues.
            Thread.sleep(1500)
            check(track.underrunCount > before) { "Forced starvation did not produce an underrun" }
            policy.checkAfterWrite()
            check(track.bufferSizeInFrames == original) { "Original buffer was not restored" }
            track.pause()
            track.play()
            val resume = System.nanoTime()
            while (System.nanoTime() - resume < 1_200_000_000L) {
                check(track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING) == samples.size)
                policy.checkAfterWrite()
            }
            check(track.bufferSizeInFrames == original) { "Fallback shrank the buffer again" }
            return "Audio output policy: original=$original reduced=$reduced; forced underrun restored original; resumed writes OK"
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}
