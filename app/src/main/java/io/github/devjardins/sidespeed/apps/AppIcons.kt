package io.github.devjardins.sidespeed.apps

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where screens get app icons: [cached] for the first frame, [load] off the main thread. */
interface IconSource {
    fun cached(pkg: String): ImageBitmap?
    suspend fun load(pkg: String): ImageBitmap?
}

/**
 * Small app icons for the list and compile header: loaded on the IO dispatcher and kept in memory.
 * Falls back to the TV banner, then the system default icon.
 */
class AppIcons(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : IconSource {
    private val pm: PackageManager = context.packageManager
    private val sizePx = (ICON_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    /** Sized by bytes (ARGB_8888), so a long app list cannot grow it past [MAX_BYTES]. */
    private val cache = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    override fun cached(pkg: String): ImageBitmap? = cache.get(pkg)

    override suspend fun load(pkg: String): ImageBitmap? {
        cache.get(pkg)?.let { return it }
        return withContext(ioDispatcher) {
            val drawable = drawableFor(pkg) ?: return@withContext null
            val bitmap = try {
                drawable.toBitmap(sizePx, sizePx).asImageBitmap()
            } catch (_: Exception) {
                null
            }
            bitmap?.also { cache.put(pkg, it) }
        }
    }

    private fun drawableFor(pkg: String): Drawable? =
        tryGet { pm.getApplicationIcon(pkg) }
            ?: tryGet { pm.getApplicationBanner(pkg) }
            ?: tryGet { pm.defaultActivityIcon }

    private inline fun tryGet(block: () -> Drawable?): Drawable? = try {
        block()
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** Rendered at about 36dp; loaded a little larger so it stays sharp. */
        const val ICON_DP = 48
        const val MAX_BYTES = 12 * 1024 * 1024
    }
}
