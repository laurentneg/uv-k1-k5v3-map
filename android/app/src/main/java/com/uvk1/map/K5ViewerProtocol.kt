package com.uvk1.map

import java.nio.ByteBuffer
import java.nio.ByteOrder

object K5ViewerProtocol {
    const val BAUD = 38400
    const val GPS_TYPE: Byte = 0x07
    const val REPORTED_POS_TYPE: Byte = 0x08
    val KEEP_ALIVE = byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x00, 0x00)

    /** Vendor/product used by Fusion UV-K1 CDC (CherryUSB). */
    const val VENDOR_ID = 0x36B7
    const val PRODUCT_ID = 0xFFFF

    fun degreesToCentiArcseconds(degrees: Double): Int =
        Math.round(degrees * 360000.0).toInt()

    fun centiArcsecondsToDegrees(cas: Int): Double = cas / 360000.0

    fun makeGpsPacket(latCas: Int, lonCas: Int): ByteArray {
        val packet = ByteArray(12)
        packet[0] = 0xAA.toByte()
        packet[1] = 0x55.toByte()
        packet[2] = GPS_TYPE
        writeI32LE(packet, 3, latCas)
        writeI32LE(packet, 7, lonCas)
        var xor = 0
        for (i in 2 until 11) xor = xor xor (packet[i].toInt() and 0xFF)
        packet[11] = xor.toByte()
        return packet
    }

    private fun writeI32LE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun readI32LE(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun labelFrom(bytes: ByteArray, offset: Int): String {
        val chars = CharArray(12)
        var n = 0
        for (i in 0 until 12) {
            val c = bytes[offset + i].toInt() and 0xFF
            if (c == 0) break
            if (c in 32 until 127) chars[n++] = c.toChar()
        }
        val s = String(chars, 0, n).trim()
        return s.ifEmpty { "target" }
    }

    data class ReportedPosition(
        val label: String,
        val latCas: Int,
        val lonCas: Int,
        val altM: Int,
        val source: Int,
    ) {
        val latDeg: Double get() = centiArcsecondsToDegrees(latCas)
        val lonDeg: Double get() = centiArcsecondsToDegrees(lonCas)
        val sourceName: String
            get() = when (source) {
                1 -> "sonde"
                2 -> "epirb"
                3 -> "aprs"
                4 -> "ais"
                else -> "other"
            }
    }

    class Parser(
        private val onReported: (ReportedPosition) -> Unit,
        private val onPosFrame: ((ok: Boolean) -> Unit)? = null,
    ) {
        private var buf = ByteArray(0)

        fun push(chunk: ByteArray) {
            if (chunk.isEmpty()) return
            val joined = ByteArray(buf.size + chunk.size)
            System.arraycopy(buf, 0, joined, 0, buf.size)
            System.arraycopy(chunk, 0, joined, buf.size, chunk.size)
            buf = joined
            // Cap reassembly buffer (screen frames can be large)
            if (buf.size > 8192) buf = buf.copyOfRange(buf.size - 4096, buf.size)
            drain()
        }

        private fun drain() {
            while (buf.size >= 3) {
                val b0 = buf[0].toInt() and 0xFF
                if ((b0 and 0xF0) == 0xF0) {
                    buf = buf.copyOfRange(1, buf.size)
                    continue
                }
                if (b0 != 0xAA) {
                    buf = buf.copyOfRange(1, buf.size)
                    continue
                }
                if (buf.size < 2) return
                if ((buf[1].toInt() and 0xFF) != 0x55) {
                    buf = buf.copyOfRange(1, buf.size)
                    continue
                }
                if (buf.size < 3) return
                val type = buf[2].toInt() and 0xFF
                when (type) {
                    REPORTED_POS_TYPE.toInt() and 0xFF -> {
                        if (buf.size < 29) return
                        val frame = buf.copyOfRange(0, 29)
                        var xor = 0
                        for (i in 2 until 28) xor = xor xor (frame[i].toInt() and 0xFF)
                        val ok = xor == (frame[28].toInt() and 0xFF)
                        onPosFrame?.invoke(ok)
                        if (ok) {
                            onReported(
                                ReportedPosition(
                                    label = labelFrom(frame, 3),
                                    latCas = readI32LE(frame, 15),
                                    lonCas = readI32LE(frame, 19),
                                    altM = readI32LE(frame, 23),
                                    source = frame[27].toInt() and 0xFF,
                                )
                            )
                        }
                        buf = buf.copyOfRange(29, buf.size)
                    }
                    0x02, 0x05, 0x06 -> {
                        if (buf.size < 5) return
                        val len = ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
                        // Reject absurd lengths (corrupt stream) instead of stalling
                        if (len > 2000) {
                            buf = buf.copyOfRange(1, buf.size)
                            continue
                        }
                        val total = 5 + len + 1
                        if (buf.size < total) return
                        buf = buf.copyOfRange(total, buf.size)
                    }
                    else -> buf = buf.copyOfRange(1, buf.size)
                }
            }
        }
    }
}
