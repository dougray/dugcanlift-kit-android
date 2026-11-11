package com.dugcanlift.liftmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Only the person's street-map download may reach the network, and only its
 * file may name the tile host. `MapNetworkGuard` alone may touch OkHttp, to
 * hand MapLibre a client that refuses every request. No map is ever built from
 * a style URL (`fromUri(`): styles come from local JSON only.
 */
class NoNetworkTest {
    private val sources = File("src/main/kotlin")

    companion object {
        /** Line comments first, so a `/*` inside one cannot eat real code up to a later `*/`. */
        fun stripComments(code: String): String =
            code.replace(Regex("//[^\n]*"), "").replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

        val network = listOf("HttpURLConnection", "openConnection", "URL(", "HttpsURLConnection", "Socket(", "URLConnection")
        const val ALLOWED_NETWORK = "MapDownload.kt"
        const val ALLOWED_OKHTTP = "MapNetworkGuard.kt"

        fun offences(name: String, code: String): List<String> {
            val bare = stripComments(code)
            val out = mutableListOf<String>()
            if (name != ALLOWED_NETWORK) {
                network.filter { bare.contains(it) }.forEach { out += "$name: $it" }
                if (bare.contains("openfreemap") || bare.lowercase().contains("openfreemap.org")) out += "$name: names the tile host"
            }
            if (name != ALLOWED_OKHTTP && bare.lowercase().contains("okhttp")) out += "$name: OkHttp"
            if (bare.contains("fromUri(") || bare.contains("fromUrl(")) out += "$name: style from a URL"
            return out
        }
    }

    @Test fun theCheckSeesWhatItLooksFor() {
        assertEquals(listOf("A.kt: openConnection"), offences("A.kt", "val c = u.openConnection()"))
        assertEquals(emptyList<String>(), offences("A.kt", "// u.openConnection()\nval x = 1"))
        assertEquals(listOf("A.kt: URL("), offences("A.kt", "/// reads src/*\nval u = URL(s)\n/* note */"))
        assertEquals(listOf("A.kt: names the tile host"), offences("A.kt", "val h = \"tiles.OpenFreeMap.org\""))
        assertEquals(emptyList<String>(), offences("A.kt", "val a = \"© OpenStreetMap contributors · OpenFreeMap\""))
        assertEquals(listOf("A.kt: OkHttp"), offences("A.kt", "import okhttp3.OkHttpClient"))
        assertEquals(listOf("A.kt: style from a URL"), offences("A.kt", "Style.Builder().fromUri(x)"))
        assertEquals(listOf("MapDownload.kt: style from a URL"), offences("MapDownload.kt", "openConnection(); fromUri(x)"))
    }

    @Test fun onlyMapDownloadMayReachTheNetwork() {
        val files = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("found too little source under ${sources.absolutePath}", files.size >= 8)
        val found = files.flatMap { offences(it.name, it.readText()) }
        assertEquals(emptyList<String>(), found)
        // The two allowances are real files, so a rename cannot quietly widen them.
        assertTrue(files.any { it.name == ALLOWED_NETWORK && it.readText().contains("tiles.openfreemap.org") })
        assertTrue(files.any { it.name == ALLOWED_OKHTTP })
    }
}
