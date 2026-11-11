package com.dugcanlift.liftmap

import android.content.Context
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Makes sure MapLibre itself can never reach a server. Every style this module
 * writes names only local files (`mbtiles://` and `asset://`), so a map has
 * nothing to fetch; this is the second lock, in case MapLibre ever asks anyway
 * (its default style, an unexpected source). MapLibre is told it is offline,
 * and its HTTP client is one whose every call fails at once, before a
 * connection is opened. The street-map download is not MapLibre's and has its
 * own connection (`MapDownload`), so it is unaffected.
 *
 * Apps call [install] once at launch, before any map is made; `RouteMap` calls
 * it too, so a map is never made without it. Calling it again does nothing.
 */
object MapNetworkGuard {
    private val installed = AtomicBoolean(false)

    fun install(ctx: Context) {
        if (!installed.compareAndSet(false, true)) return
        MapLibre.getInstance(ctx.applicationContext)
        MapLibre.setConnected(false)
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .addInterceptor { throw IOException("LIFT maps make no requests") }
                .build(),
        )
    }
}
