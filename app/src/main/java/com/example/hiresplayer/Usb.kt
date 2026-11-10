package com.example.hiresplayer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

object NativeUsb {
    init { System.loadLibrary("usbaudio") }
    external fun open(fd: Int, ep: Int, maxPkt: Int, rate: Int, ch: Int, sub: Int, mode: Int, interval: Int): Boolean
    external fun write(data: ByteArray, n: Int): Int
    external fun setPlaying(p: Boolean)
    external fun flush()
    external fun position(): Long
    external fun drained(): Boolean
    external fun close()
}

class Fmt(var ch: Int = 0, var sub: Int = 0, var bits: Int = 0, var rates: List<Int> = emptyList())
class Parsed(val map: Map<Pair<Int, Int>, Fmt>, val uac2: Boolean, val clock: Int, val ac: Int)

/** Reads the USB Audio Class descriptors (UAC1 and UAC2) to learn each alt setting's format. */
fun parseUac(raw: ByteArray): Parsed {
    var uac2 = false; var clock = 0; var ac = -1
    val map = HashMap<Pair<Int, Int>, Fmt>()
    var i = 0; var ifn = 0; var alt = 0; var sc = 0
    while (i + 2 < raw.size) {
        val len = raw[i].toInt() and 255
        if (len < 2) break
        val base = i
        fun b(k: Int) = if (base + k < raw.size) raw[base + k].toInt() and 255 else 0
        val t = b(1)
        if (t == 4) { ifn = b(2); alt = b(3); sc = b(6) }
        else if (t == 0x24) {
            val st = b(2)
            if (sc == 1) { if (st == 1) { uac2 = b(4) == 2; ac = ifn } else if (st == 0x0A) clock = b(3) }
            else if (sc == 2) {
                val f = map.getOrPut(ifn to alt) { Fmt() }
                if (st == 1 && uac2) f.ch = b(10)
                if (st == 2) {
                    if (uac2) { f.sub = b(4); f.bits = b(5) }
                    else {
                        f.ch = b(4); f.sub = b(5); f.bits = b(6)
                        val n = b(7)
                        f.rates = if (n == 0) emptyList() else List(n) { k -> b(8 + 3 * k) or (b(9 + 3 * k) shl 8) or (b(10 + 3 * k) shl 16) }
                    }
                }
            }
        }
        i += len
    }
    return Parsed(map, uac2, clock, ac)
}

class UsbExclusive(private val c: Context) {
    private val um = c.getSystemService(UsbManager::class.java)
    private var conn: UsbDeviceConnection? = null

    private fun ifaces(d: UsbDevice) = (0 until d.interfaceCount).map { d.getInterface(it) }
    fun dac(): UsbDevice? = um.deviceList.values.firstOrNull { d -> ifaces(d).any { it.interfaceClass == 1 && it.interfaceSubclass == 2 } }

    fun requestPermission() {
        val d = dac() ?: return
        if (!um.hasPermission(d)) um.requestPermission(d,
            PendingIntent.getBroadcast(c, 0, Intent("com.example.hiresplayer.USB").setPackage(c.packageName), PendingIntent.FLAG_MUTABLE))
    }

    /** Opens the DAC at exactly [rate] Hz. mode: 0=16-bit, 1=24-bit, 2=32-bit, 3=float input. */
    fun open(rate: Int, src16: Boolean, mode: Int): Boolean {
        val d = dac() ?: return false
        if (!um.hasPermission(d)) return false
        close()
        val cn = um.openDevice(d) ?: return false
        val p = parseUac(cn.rawDescriptors)
        var best: Triple<UsbInterface, Fmt, UsbEndpoint>? = null; var bestScore = -1
        for (itf in ifaces(d)) {
            if (itf.interfaceClass != 1 || itf.interfaceSubclass != 2) continue
            val f = p.map[itf.id to itf.alternateSetting] ?: continue
            if (f.ch != 2 || f.sub !in 2..4 || (f.rates.isNotEmpty() && rate !in f.rates)) continue
            val e = (0 until itf.endpointCount).map { itf.getEndpoint(it) }.firstOrNull {
                it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && it.direction == UsbConstants.USB_DIR_OUT } ?: continue
            val score = if (src16 && f.bits == 16) 100 else f.bits
            if (score > bestScore) { bestScore = score; best = Triple(itf, f, e) }
        }
        val (intf, f, e) = best ?: run { cn.close(); return false }
        ifaces(d).firstOrNull { it.id == p.ac }?.let { cn.claimInterface(it, true) }
        if (!cn.claimInterface(intf, true) || !cn.setInterface(intf)) { cn.close(); return false }
        val buf = ByteArray(4) { ((rate shr (8 * it)) and 255).toByte() }
        val ok = if (p.uac2) cn.controlTransfer(0x21, 1, 0x0100, (p.clock shl 8) or p.ac, buf, 4, 500) >= 0
                 else { cn.controlTransfer(0x22, 1, 0x0100, e.address, buf, 3, 500); true }
        if (!ok || !NativeUsb.open(cn.fileDescriptor, e.address, e.maxPacketSize and 0x7FF, rate, 2, f.sub, mode, e.interval)) {
            cn.close(); return false
        }
        conn = cn
        return true
    }

    fun close() {
        if (conn == null) return
        NativeUsb.close(); conn?.close(); conn = null
    }
}
