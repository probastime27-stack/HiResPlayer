package com.example.hiresplayer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore.Audio.Media
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Theme colours: change ACCENT to match your preferred KMPlayer shade.
val BLACK = Color(0xFF000000)
val PANEL = Color(0xFF141414)
val ACCENT = Color(0xFFFFC400)
val DIM = Color(0xFF9A9A9A)

data class Track(val id: Long, val title: String, val artist: String, val album: String, val uri: Uri)
data class Tech(val codec: String = "—", val bits: String = "—", val rate: String = "—", val bitrate: String = "—")
data class Dac(val name: String, val rates: List<Int>, val enc: List<String>, val channels: List<Int>, val bitPerfect: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = ACCENT, background = BLACK, surface = BLACK)) {
                Surface(Modifier.fillMaxSize(), color = BLACK) { App() }
            }
        }
    }
}

fun loadTracks(c: Context): List<Track> {
    val out = mutableListOf<Track>()
    val proj = arrayOf(Media._ID, Media.TITLE, Media.ARTIST, Media.ALBUM)
    c.contentResolver.query(Media.EXTERNAL_CONTENT_URI, proj, "${Media.IS_MUSIC}!=0", null, "${Media.TITLE} COLLATE NOCASE")?.use { q ->
        while (q.moveToNext()) {
            val id = q.getLong(0)
            out += Track(id, q.getString(1) ?: "", q.getString(2) ?: "", q.getString(3) ?: "",
                ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, id))
        }
    }
    return out
}

suspend fun loadArt(c: Context, t: Track): Bitmap? = withContext(Dispatchers.IO) {
    try { c.contentResolver.loadThumbnail(t.uri, Size(700, 700), null) } catch (e: Exception) { null }
}

suspend fun loadTech(c: Context, t: Track): Tech = withContext(Dispatchers.IO) {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(c, t.uri)
        val mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.substringAfter('/')?.uppercase()
        val codec = when (mime) { "MPEG" -> "MP3"; null -> "—"; else -> mime.removePrefix("X-") }
        val br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
        var rate = "—"; var bits = "—"
        if (Build.VERSION.SDK_INT >= 31) {
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()?.let { rate = "${it / 1000.0} kHz" }
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()?.takeIf { it > 0 }?.let { bits = "$it-bit" }
        }
        Tech(codec, bits, rate, br?.let { "${it / 1000} kbps" } ?: "—")
    } catch (e: Exception) { Tech() } finally { r.release() }
}

fun encName(e: Int) = when (e) {
    AudioFormat.ENCODING_PCM_16BIT -> "16-bit"
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit"
    AudioFormat.ENCODING_PCM_32BIT -> "32-bit"
    AudioFormat.ENCODING_PCM_FLOAT -> "32-bit float"
    else -> "enc $e"
}

fun loadDacs(c: Context): List<Dac> {
    val am = c.getSystemService(AudioManager::class.java)
    return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filter { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        .map { d ->
            val bp = Build.VERSION.SDK_INT >= 34 && am.getSupportedMixerAttributes(d)
                .any { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            Dac(d.productName.toString(), d.sampleRates.sorted(), d.encodings.map { encName(it) }.distinct(), d.channelCounts.sorted(), bp)
        }
}

fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

@Composable
fun App() {
    val c = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(perm) }

    val tracks = remember(granted) { if (granted) loadTracks(c) else emptyList() }

    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val f = MediaController.Builder(c, SessionToken(c, ComponentName(c, PlayerService::class.java))).buildAsync()
        f.addListener({ controller = f.get() }, ContextCompat.getMainExecutor(c))
        onDispose { MediaController.releaseFuture(f) }
    }

    var playing by remember { mutableStateOf(false) }
    var curId by remember { mutableStateOf<String?>(null) }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    DisposableEffect(controller) {
        val p = controller ?: return@DisposableEffect onDispose {}
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(m: MediaItem?, reason: Int) { curId = m?.mediaId }
        }
        p.addListener(l); playing = p.isPlaying; curId = p.currentMediaItem?.mediaId
        onDispose { p.removeListener(l) }
    }
    LaunchedEffect(controller) {
        while (true) { controller?.let { pos = it.currentPosition; dur = it.duration.coerceAtLeast(0) }; delay(400) }
    }

    var dacs by remember { mutableStateOf(loadDacs(c)) }
    DisposableEffect(Unit) {
        val am = c.getSystemService(AudioManager::class.java)
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(d: Array<out AudioDeviceInfo>?) { dacs = loadDacs(c) }
            override fun onAudioDevicesRemoved(d: Array<out AudioDeviceInfo>?) { dacs = loadDacs(c) }
        }
        am.registerAudioDeviceCallback(cb, null)
        onDispose { am.unregisterAudioDeviceCallback(cb) }
    }
    var showDac by remember { mutableStateOf(false) }
    var excl by remember { mutableStateOf(c.getSharedPreferences("p", 0).getBoolean("excl", false)) }

    fun playAt(i: Int) {
        val p = controller ?: return
        p.setMediaItems(tracks.map {
            MediaItem.Builder().setMediaId(it.id.toString()).setUri(it.uri).setMediaMetadata(
                MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist).setAlbumTitle(it.album).build()).build()
        }, i, 0)
        p.prepare(); p.play()
    }

    val cur = tracks.firstOrNull { it.id.toString() == curId }
    val art by produceState<Bitmap?>(null, cur) { value = cur?.let { loadArt(c, it) } }
    val tech by produceState(Tech(), cur) { value = cur?.let { loadTech(c, it) } ?: Tech() }
    var drag by remember { mutableStateOf<Float?>(null) }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("HiRes Player", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val on = dacs.isNotEmpty()
            Text(if (on) "USB DAC" else "No DAC", color = if (on) BLACK else DIM, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) ACCENT else PANEL)
                    .clickable { showDac = true }.padding(horizontal = 12.dp, vertical = 6.dp))
        }

        Box(Modifier.fillMaxWidth(0.72f).aspectRatio(1f).align(Alignment.CenterHorizontally)
            .clip(RoundedCornerShape(16.dp)).background(PANEL), contentAlignment = Alignment.Center) {
            val b = art
            if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text("♪", color = ACCENT, fontSize = 72.sp)
        }

        Spacer(Modifier.height(12.dp))
        Text(cur?.title ?: "Select a song", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(cur?.artist ?: "", color = DIM, fontSize = 14.sp, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(tech.codec, tech.bits, tech.rate, tech.bitrate).forEach {
                Text(it, color = ACCENT, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background(PANEL).padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }

        val frac = if (dur > 0) pos.toFloat() / dur else 0f
        Slider(value = drag ?: frac, onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let { controller?.seekTo((it * dur).toLong()) }; drag = null },
            colors = SliderDefaults.colors(thumbColor = ACCENT, activeTrackColor = ACCENT, inactiveTrackColor = PANEL))
        Row(Modifier.fillMaxWidth()) {
            Text(fmt(pos), color = DIM, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(fmt(dur), color = DIM, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TextButton({ controller?.seekToPrevious() }) { Text("⏮", color = Color.White, fontSize = 28.sp) }
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(50)).background(ACCENT)
                .clickable { controller?.let { if (it.isPlaying) it.pause() else it.play() } }, contentAlignment = Alignment.Center) {
                Text(if (playing) "⏸" else "▶", color = BLACK, fontSize = 28.sp)
            }
            TextButton({ controller?.seekToNext() }) { Text("⏭", color = Color.White, fontSize = 28.sp) }
        }

        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            itemsIndexed(tracks) { i, t ->
                val sel = t.id.toString() == curId
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (sel) PANEL else BLACK)
                    .clickable { playAt(i) }.padding(12.dp)) {
                    Text(t.title, color = if (sel) ACCENT else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, color = DIM, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }

    if (showDac) AlertDialog(onDismissRequest = { showDac = false }, containerColor = PANEL,
        confirmButton = { TextButton({ showDac = false }) { Text("Close", color = ACCENT) } },
        title = { Text("USB DAC", color = Color.White) },
        text = {
            if (dacs.isEmpty()) Text("No USB DAC connected.", color = DIM)
            else Column { dacs.forEach { d ->
                Text(d.name, color = ACCENT, fontWeight = FontWeight.Bold)
                Text("Sample rates: " + d.rates.joinToString { "${it / 1000.0} kHz" }.ifEmpty { "auto" }, color = Color.White, fontSize = 13.sp)
                Text("Formats: " + d.enc.joinToString().ifEmpty { "auto" }, color = Color.White, fontSize = 13.sp)
                Text("Channels: " + d.channels.joinToString().ifEmpty { "auto" }, color = Color.White, fontSize = 13.sp)
                Text("Bit-perfect mode: " + if (d.bitPerfect) "available" else "not supported", color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
            }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Exclusive USB mode (experimental, applies from next song)", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = excl, onCheckedChange = {
                        excl = it; c.getSharedPreferences("p", 0).edit().putBoolean("excl", it).apply()
                        if (it) UsbExclusive(c).requestPermission()
                    })
                }
            }
        })
}
