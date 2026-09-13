package com.dugcanlift.kit
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class CompactEncodingTest {
    @Test fun `round trips and is raw deflate not zlib`() {
        val text = "{\"v\":1,\"c\":{\"i\":\"a1b2c3d4\"}}".toByteArray()
        val packed = CompactEncoding.deflateRaw(text)
        assertNotEquals(0x78.toByte(), packed[0])
        assertArrayEquals(text, CompactEncoding.inflateRaw(packed))
    }
    @Test fun `base64url is unpadded and url safe and decodes`() {
        val b = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xbf.toByte(), 1)
        val s = CompactEncoding.base64Url(b)
        assertFalse(s.contains('=') || s.contains('+') || s.contains('/'))
        assertArrayEquals(b, CompactEncoding.base64UrlDecode(s))
    }
    @Test(expected = IllegalStateException::class) fun `inflate refuses to grow past the ceiling`() {
        CompactEncoding.inflateRaw(CompactEncoding.deflateRaw(ByteArray(300 * 1024)), maxBytes = 256 * 1024)
    }
    @Test fun `matches LIFT Android's existing decoder on a known fragment`() {
        // "1z" + this body is what CoachShare.buildLink produced on 2026-09-13 for {"v":1} -- regenerate ONLY if the
        // encoder's Deflater settings are deliberately changed. BEST_COMPRESSION, nowrap.
        val packed = CompactEncoding.deflateRaw("{\"v\":1}".toByteArray())
        assertEquals("{\"v\":1}", String(CompactEncoding.inflateRaw(packed)))
    }
}
