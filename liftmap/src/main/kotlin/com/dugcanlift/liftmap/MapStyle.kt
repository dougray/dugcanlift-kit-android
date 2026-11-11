package com.dugcanlift.liftmap

import android.content.Context
import java.io.File
import java.net.URI

/**
 * The street styles, filled with this device's own files. No server is named:
 * the tiles are the MBTiles store (`mbtiles://`) and the glyphs are this
 * module's assets (`asset://`). A map is only ever built from this JSON
 * (`Style.Builder().fromJson`), never from a style URL.
 */
object MapStyle {
    /** The bundled fonts; MapLibre fills `{fontstack}` and `{range}`. */
    const val GLYPHS = "asset://map/fonts/{fontstack}/{range}.pbf"

    fun assetName(dark: Boolean) = if (dark) "style-dark.json" else "style-light.json"

    /** `mbtiles://` plus the percent-encoded absolute path; MapLibre decodes it again. */
    fun mbtilesUrl(file: File): String =
        "mbtiles://" + URI(null, null, file.absolutePath, null).toASCIIString()

    /** The style for [dark] or light, drawing from [store]. */
    fun json(ctx: Context, dark: Boolean, store: File): String {
        val template = ctx.assets.open("map/${assetName(dark)}").use { it.readBytes().toString(Charsets.UTF_8) }
        return fill(template, store)
    }

    internal fun fill(template: String, store: File): String =
        template.replace("{{TILES}}", mbtilesUrl(store)).replace("{{GLYPHS}}", GLYPHS)
}
