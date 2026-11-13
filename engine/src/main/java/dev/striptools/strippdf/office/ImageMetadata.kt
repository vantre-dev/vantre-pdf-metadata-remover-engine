package dev.striptools.strippdf.office

import java.io.ByteArrayOutputStream

/**
 * Photos pasted into Office documents keep their camera EXIF (often GPS). This reads
 * and strips EXIF / XMP / IPTC / comments from JPEG and text / EXIF chunks from PNG
 * without re-encoding pixels.
 */
internal object ImageMetadata {

    data class Info(val hasMetadata: Boolean, val hasGps: Boolean)

    private val NONE = Info(hasMetadata = false, hasGps = false)

    fun isJpeg(b: ByteArray) = b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
    fun isPng(b: ByteArray) = b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
        b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    fun inspect(b: ByteArray): Info = when {
        isJpeg(b) -> inspectJpeg(b)
        isPng(b) -> inspectPng(b)
        else -> NONE
    }

    /** Returns cleaned bytes, or the input itself when nothing needed removing. */
    fun strip(b: ByteArray): ByteArray = when {
        isJpeg(b) -> runCatching { stripJpeg(b) }.getOrDefault(b)
        isPng(b) -> runCatching { stripPng(b) }.getOrDefault(b)
        else -> b
    }

    // ── JPEG ──────────────────────────────────────────────────────────────

    private class Segment(val marker: Int, val start: Int, val dataStart: Int, val end: Int)

    /** Segments before SOS, plus the offset where entropy-coded data begins. */
    private fun jpegSegments(b: ByteArray): Pair<List<Segment>, Int> {
        val segs = mutableListOf<Segment>()
        var p = 2
        while (p + 1 < b.size) {
            if (b[p] != 0xFF.toByte()) return segs to p
            var q = p
            while (q < b.size && b[q] == 0xFF.toByte()) q++
            if (q >= b.size) break
            val marker = b[q].toInt() and 0xFF
            val segStart = q - 1
            if (marker == 0xD9 || marker == 0xDA) return segs to segStart
            if (marker == 0x01 || marker in 0xD0..0xD7) { p = q + 1; continue }
            if (q + 2 >= b.size) break
            val len = ((b[q + 1].toInt() and 0xFF) shl 8) or (b[q + 2].toInt() and 0xFF)
            val end = q + 1 + len
            if (len < 2 || end > b.size) return segs to segStart
            segs += Segment(marker, segStart, q + 3, end)
            p = end
        }
        return segs to b.size
    }

    private fun isExif(b: ByteArray, s: Segment) =
        s.marker == 0xE1 && s.end - s.dataStart >= 6 && String(b, s.dataStart, 4, Charsets.ISO_8859_1) == "Exif"

    private fun isRemovable(s: Segment) = s.marker == 0xE1 || s.marker == 0xED || s.marker == 0xFE

    private fun inspectJpeg(b: ByteArray): Info {
        val (segs, _) = jpegSegments(b)
        var meta = false
        var gps = false
        for (s in segs) {
            if (!isRemovable(s)) continue
            if (isExif(b, s)) {
                val tiff = TiffIfd(b, s.dataStart + 6, s.end)
                // A lone orientation tag is what we write back ourselves; it carries nothing personal.
                if (tiff.hasGps()) gps = true
                if (!tiff.isOrientationOnly()) meta = true
            } else {
                meta = true
            }
        }
        return Info(meta || gps, gps)
    }

    private fun stripJpeg(b: ByteArray): ByteArray {
        val (segs, scanStart) = jpegSegments(b)
        if (segs.none { isRemovable(it) && !(isExif(b, it) && TiffIfd(b, it.dataStart + 6, it.end).isOrientationOnly()) }) return b
        val orientation = segs.firstOrNull { isExif(b, it) }?.let { TiffIfd(b, it.dataStart + 6, it.end).orientation() } ?: 1
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 2)
        var wroteOrientation = false
        for (s in segs) {
            if (isRemovable(s)) {
                // Keep rotation so pictures don't flip sideways in the document.
                if (orientation != 1 && !wroteOrientation && isExif(b, s)) {
                    out.write(orientationOnlyExif(orientation)); wroteOrientation = true
                }
                continue
            }
            out.write(b, s.start, s.end - s.start)
        }
        out.write(b, scanStart, b.size - scanStart)
        return out.toByteArray()
    }

    private fun orientationOnlyExif(orientation: Int): ByteArray {
        val body = byteArrayOf(
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
            'M'.code.toByte(), 'M'.code.toByte(), 0, 0x2A, 0, 0, 0, 8,
            0, 1,                                   // one IFD0 entry
            0x01, 0x12, 0, 3, 0, 0, 0, 1,           // Orientation, SHORT, count 1
            0, orientation.toByte(), 0, 0,
            0, 0, 0, 0                              // no next IFD
        )
        val len = body.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + body
    }

    // ── PNG ───────────────────────────────────────────────────────────────

    private val PNG_DROP = setOf("tEXt", "zTXt", "iTXt", "eXIf", "tIME")

    private inline fun pngChunks(b: ByteArray, visit: (type: String, start: Int, dataStart: Int, end: Int) -> Unit) {
        var p = 8
        while (p + 12 <= b.size) {
            val len = ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
                ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            if (len < 0 || p + 12L + len > b.size) break
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            visit(type, p, p + 8, p + 12 + len)
            p += 12 + len
        }
    }

    private fun inspectPng(b: ByteArray): Info {
        var meta = false
        var gps = false
        pngChunks(b) { type, _, dataStart, end ->
            if (type in PNG_DROP) meta = true
            if (type == "eXIf" && TiffIfd(b, dataStart, end - 4).hasGps()) gps = true
        }
        return Info(meta, gps)
    }

    private fun stripPng(b: ByteArray): ByteArray {
        if (!inspectPng(b).hasMetadata) return b
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 8)
        var lastEnd = 8
        pngChunks(b) { type, start, _, end ->
            if (type !in PNG_DROP) out.write(b, start, end - start)
            lastEnd = end
        }
        if (lastEnd < b.size) out.write(b, lastEnd, b.size - lastEnd)
        return out.toByteArray()
    }

    // ── TIFF / EXIF IFD0 ──────────────────────────────────────────────────

    private class TiffIfd(private val b: ByteArray, private val base: Int, private val limit: Int) {
        private val le = base + 1 < limit && b[base] == 'I'.code.toByte() && b[base + 1] == 'I'.code.toByte()

        private fun u16(off: Int): Int {
            val p = base + off
            if (p + 1 >= limit) return -1
            val a = b[p].toInt() and 0xFF; val c = b[p + 1].toInt() and 0xFF
            return if (le) a or (c shl 8) else (a shl 8) or c
        }

        private fun u32(off: Int): Long {
            val hi = u16(if (le) off + 2 else off); val lo = u16(if (le) off else off + 2)
            if (hi < 0 || lo < 0) return -1
            return (hi.toLong() shl 16) or lo.toLong()
        }

        private fun entries(): List<Pair<Int, Int>> {
            val ifd = u32(4)
            if (ifd < 8 || base + ifd + 2 > limit) return emptyList()
            val count = u16(ifd.toInt())
            if (count <= 0 || count > 512) return emptyList()
            return (0 until count).mapNotNull { i ->
                val e = ifd.toInt() + 2 + i * 12
                val tag = u16(e)
                if (tag < 0) null else tag to e
            }
        }

        fun hasGps(): Boolean = entries().any { (tag, e) ->
            if (tag != 0x8825) return@any false
            val gpsIfd = u32(e + 8)
            gpsIfd > 0 && base + gpsIfd + 2 <= limit && u16(gpsIfd.toInt()) > 0
        }

        fun orientation(): Int = entries().firstOrNull { it.first == 0x0112 }?.let { (_, e) ->
            u16(e + 8).takeIf { it in 1..8 }
        } ?: 1

        fun isOrientationOnly(): Boolean = entries().let { it.size == 1 && it[0].first == 0x0112 }
    }
}
