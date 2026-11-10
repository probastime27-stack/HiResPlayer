package com.example.hiresplayer

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * Sends PCM straight to the USB DAC (own driver, no Android mixer/resampler) when "Exclusive USB mode"
 * is on and the DAC supports the track's exact sample rate. Otherwise falls back to the normal sink.
 */
@OptIn(UnstableApi::class)
class ExclusiveSink(c: Context, base: AudioSink) : ForwardingAudioSink(base) {
    private val usb = UsbExclusive(c)
    private val prefs = c.getSharedPreferences("p", 0)
    private val scratch = ByteArray(1 shl 16)
    private var ex = false
    private var end = false
    private var wantPlay = false
    private var rate = 1

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        ex = false
        val mode = when (inputFormat.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 0; C.ENCODING_PCM_24BIT -> 1; C.ENCODING_PCM_32BIT -> 2; C.ENCODING_PCM_FLOAT -> 3; else -> -1
        }
        if (prefs.getBoolean("excl", false) && mode >= 0 && inputFormat.channelCount == 2 &&
            usb.open(inputFormat.sampleRate, mode == 0, mode)) {
            ex = true; end = false; rate = inputFormat.sampleRate
            NativeUsb.setPlaying(wantPlay)
            return
        }
        usb.close()
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!ex) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        while (buffer.hasRemaining()) {
            val n = minOf(buffer.remaining(), scratch.size)
            buffer.get(scratch, 0, n)
            val used = NativeUsb.write(scratch, n)
            if (used < n) { buffer.position(buffer.position() - (n - used)); return false }
        }
        return true
    }

    override fun play() { wantPlay = true; if (ex) NativeUsb.setPlaying(true) else super.play() }
    override fun pause() { wantPlay = false; if (ex) NativeUsb.setPlaying(false) else super.pause() }
    override fun flush() { if (ex) { NativeUsb.flush(); end = false } else super.flush() }
    override fun reset() { if (ex) { usb.close(); ex = false }; super.reset() }
    override fun playToEndOfStream() { if (ex) end = true else super.playToEndOfStream() }
    override fun isEnded() = if (ex) end && NativeUsb.drained() else super.isEnded()
    override fun hasPendingData() = if (ex) !NativeUsb.drained() else super.hasPendingData()
    override fun getCurrentPositionUs(sourceEnded: Boolean) =
        if (ex) NativeUsb.position() * 1_000_000L / rate else super.getCurrentPositionUs(sourceEnded)
    override fun setVolume(volume: Float) { if (!ex) super.setVolume(volume) }
}
