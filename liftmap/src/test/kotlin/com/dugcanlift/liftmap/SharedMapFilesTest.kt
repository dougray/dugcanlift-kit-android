package com.dugcanlift.liftmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The styles and fonts are dugcanlift-kit's `map/` files, copied byte for byte
 * with the kit's `map-files.sha256`. The manifest may also list files this
 * module does not carry (the web build's), so only the style and font lines are
 * checked -- and every one of them must be here.
 */
class SharedMapFilesTest {
    private val root = File("src/main/assets/map")

    private fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
        .joinToString("") { "%02x".format(it) }

    @Test fun theAssetsAreTheKitsBytes() {
        val lines = File(root, "map-files.sha256").readLines().filter { it.isNotEmpty() }
        var checked = 0
        for (line in lines) {
            val parts = line.split("  ", limit = 2)
            assertEquals("bad line $line", 2, parts.size)
            val path = parts[1]
            if (!path.startsWith("fonts/") && !path.startsWith("style-")) continue
            val file = File(root, path)
            assertTrue("$path is in the kit's manifest and missing here", file.isFile)
            assertEquals("$path differs from the kit's shared copy", parts[0], sha256(file))
            checked++
        }
        assertTrue("expected the two styles and the fonts, checked $checked", checked >= 8)
    }

    @Test fun everyStyleAndFontHereIsInTheManifest() {
        val listed = File(root, "map-files.sha256").readLines().filter { it.isNotEmpty() }
            .map { it.split("  ", limit = 2)[1] }.toSet()
        val here = root.walkTopDown().filter { it.isFile && it.name != "map-files.sha256" }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        for (path in here) assertTrue("$path is not the kit's", path in listed)
    }

    /**
     * MapLibre never wakes a tile waiting on a glyph range whose file is
     * missing, so every one of the 256 ranges ships: the five with glyphs, and
     * an empty file for each of the rest. Each is in the manifest and matches it.
     */
    @Test fun everyGlyphRangeShips() {
        val sums = File(root, "map-files.sha256").readLines().filter { it.isNotEmpty() }
            .associate { line -> line.split("  ", limit = 2).let { it[1] to it[0] } }
        val dir = File(root, "fonts/Noto Sans Regular")
        for (start in 0..65280 step 256) {
            val path = "fonts/Noto Sans Regular/$start-${start + 255}.pbf"
            val file = File(root, path)
            assertTrue("$path is missing", file.isFile)
            assertEquals("$path is not in the manifest or differs from it", sums[path], sha256(file))
        }
        assertEquals(256, dir.listFiles { f -> f.name.endsWith(".pbf") }!!.size)
    }

    @Test fun theManifestListsExactlyTheFilesHere() {
        val listed = File(root, "map-files.sha256").readLines().filter { it.isNotEmpty() }
            .map { it.split("  ", limit = 2)[1] }
        val here = root.walkTopDown().filter { it.isFile && it.name != "map-files.sha256" }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        assertEquals(here.sorted(), listed.sorted())
        assertEquals("sorted by path", listed.sorted(), listed)
    }
}
