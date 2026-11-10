package com.example.hiresplayer

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Plays audio in the background and routes it to a USB DAC (bit-perfect on Android 14+). */
@OptIn(UnstableApi::class)
class PlayerService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var am: AudioManager

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(d: Array<out AudioDeviceInfo>?) = route()
        override fun onAudioDevicesRemoved(d: Array<out AudioDeviceInfo>?) = route()
    }

    override fun onCreate() {
        super.onCreate()
        am = getSystemService(AudioManager::class.java)
        // Float output keeps 24-bit+ precision through the pipeline.
        val rf = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                ExclusiveSink(context, DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams).build())
        }.setEnableAudioFloatOutput(true)
        player = ExoPlayer.Builder(this, rf)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) = route()
        })
        session = MediaSession.Builder(this, player).build()
        am.registerAudioDeviceCallback(callback, null)
        route()
    }

    private fun route() {
        val usb = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        player.setPreferredAudioDevice(usb)
        if (usb != null && Build.VERSION.SDK_INT >= 34) {
            val rate = player.currentTracks.groups
                .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                ?.getTrackFormat(0)?.sampleRate ?: Format.NO_VALUE
            val bp = am.getSupportedMixerAttributes(usb)
                .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            val pick = bp.firstOrNull { it.format.sampleRate == rate } ?: bp.firstOrNull()
            val aa = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
            if (pick != null) am.setPreferredMixerAttributes(aa, usb, pick)
            else am.clearPreferredMixerAttributes(aa, usb)
        }
    }

    override fun onGetSession(info: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        am.unregisterAudioDeviceCallback(callback)
        session?.release(); player.release(); session = null
        super.onDestroy()
    }
}
