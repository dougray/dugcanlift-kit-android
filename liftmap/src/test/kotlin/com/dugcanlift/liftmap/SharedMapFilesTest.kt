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
}
