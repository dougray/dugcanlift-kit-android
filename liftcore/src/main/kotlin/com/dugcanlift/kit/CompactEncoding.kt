package com.dugcanlift.kit
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/** The one envelope implementation for SHARE-FORMAT and PLAN-FORMAT: raw DEFLATE (no zlib wrapper) + base64url. */
object CompactEncoding {
    fun deflateRaw(bytes: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true); d.setInput(bytes); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf)); d.end(); return out.toByteArray()
    }
    /** Throws IllegalStateException past `maxBytes` -- far more than any legitimate link, guards a zip-bomb-shaped fragment. */
    fun inflateRaw(bytes: ByteArray, maxBytes: Int = 256 * 1024): ByteArray {
        val i = Inflater(true); i.setInput(bytes)
        val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
        try {
            while (!i.finished()) {
                val n = i.inflate(buf)
                if (n == 0 && (i.needsInput() || i.needsDictionary())) error("truncated deflate stream")
                out.write(buf, 0, n); check(out.size() <= maxBytes) { "inflated payload exceeds $maxBytes bytes" }
            }
        } finally { i.end() }
        return out.toByteArray()
    }
    fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun base64UrlDecode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}
