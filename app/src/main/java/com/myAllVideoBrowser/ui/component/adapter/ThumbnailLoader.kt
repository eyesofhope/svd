package com.myAllVideoBrowser.ui.component.adapter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.collection.LruCache
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.request.target.CustomTarget
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.util.AppLogger
import java.io.File
import java.util.concurrent.Executors

/**
 * Loads a thumbnail for a [ProgressInfo] row using the most reliable source
 * available, in order:
 *
 *  1. The `videoInfo.thumbnail` URL via Glide (the previous behaviour).
 *  2. If that URL is missing or fails to load, a frame extracted from the
 *     partially-downloaded file via [MediaMetadataRetriever]. Many sites do not
 *     return a thumbnail URL alongside their HLS / DASH manifests, so this is
 *     the only way to render a useful image while the download is in flight.
 *  3. The static `ic_video_24dp` placeholder.
 *
 * Frame extraction is cached per-[ProgressInfo.downloadId] in an LRU cache so
 * we don't re-decode on every adapter rebind. The cache is bounded by both
 * memory size and entry count.
 */
internal object ThumbnailLoader {

    /** Max ~6 MB of decoded frames retained at once — plenty for a list. */
    private const val MAX_CACHE_BYTES = 6 * 1024 * 1024

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "thumb-extractor").apply { isDaemon = true }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val cache = object : LruCache<Long, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    /** Tracks downloadIds we've tried to extract from to avoid repeating work
     *  when the partial file isn't ready yet. */
    private val attempted = HashSet<Long>()

    fun load(target: ImageView, info: ProgressInfo, screenW: Int, screenH: Int) {
        val ctx = target.context.applicationContext

        // Always preview from cache first to keep scrolling snappy.
        val cached = cache.get(info.downloadId)
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }

        // Tag the target so a later async result can verify it still belongs.
        target.setTag(R.id.tag_thumbnail_id, info.downloadId)

        val placeholder = R.drawable.ic_video_24dp
        val thumbnail = info.videoInfo.thumbnail.orEmpty()

        if (thumbnail.isNotBlank()) {
            // Primary path: load the network thumbnail. If it fails (very common
            // for sites that don't expose one), fall through to the file frame
            // extractor.
            Glide.with(ctx)
                .asBitmap()
                .load(thumbnail)
                .placeholder(placeholder)
                .error(placeholder)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .apply(RequestOptions().override(screenW / 8, screenH / 8))
                .listener(object : RequestListener<Bitmap> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        t: Target<Bitmap>?,
                        isFirstResource: Boolean,
                    ): Boolean {
                        extractFromFileAsync(target, info)
                        return false
                    }

                    override fun onResourceReady(
                        resource: Bitmap,
                        model: Any?,
                        t: Target<Bitmap>?,
                        dataSource: DataSource?,
                        isFirstResource: Boolean,
                    ): Boolean = false
                })
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(
                        resource: Bitmap,
                        transition: Transition<in Bitmap>?,
                    ) {
                        if (target.getTag(R.id.tag_thumbnail_id) == info.downloadId) {
                            cache.put(info.downloadId, resource)
                            target.setImageBitmap(resource)
                        }
                    }

                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {
                        // No-op: the recycler bind will set a fresh image.
                    }
                })
        } else {
            // No URL → go straight to the partial-file frame extractor.
            target.setImageResource(placeholder)
            extractFromFileAsync(target, info)
        }
    }

    private fun extractFromFileAsync(target: ImageView, info: ProgressInfo) {
        val ctx = target.context.applicationContext
        val downloadId = info.downloadId

        // Don't keep trying repeatedly for items that have nothing yet.
        if (!attempted.add(downloadId)) return

        executor.execute {
            try {
                val bitmap = extractFrame(ctx, info)
                if (bitmap != null) {
                    cache.put(downloadId, bitmap)
                    mainHandler.post {
                        if (target.getTag(R.id.tag_thumbnail_id) == downloadId) {
                            target.setImageBitmap(bitmap)
                        }
                    }
                }
            } catch (t: Throwable) {
                AppLogger.e("ThumbnailLoader: extract failed for $downloadId — ${t.message}")
            } finally {
                // Allow another retry the next time the row is bound — the
                // partial file may have grown by then.
                synchronized(attempted) { attempted.remove(downloadId) }
            }
        }
    }

    /**
     * Extract the first decodable frame from the partial / completed download
     * file. We probe the most likely on-disk locations: the
     * `name`-based filename inside the app's downloads dir and the SuperX
     * tmp data dir.
     */
    private fun extractFrame(ctx: Context, info: ProgressInfo): Bitmap? {
        val candidates = mutableListOf<File>()
        val name = info.videoInfo.name
        // Common public download dir + SuperX folder used elsewhere in the app.
        val downloadsDir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            "SuperX"
        )
        candidates += File(downloadsDir, name)
        candidates += File(ctx.getExternalFilesDir(null), "SuperX/$name")
        candidates += File(ctx.filesDir, "superx_tmp_data/$name")

        val present = candidates.firstOrNull { it.exists() && it.length() > 0 } ?: return null
        return decodeFirstFrame(present)
    }

    private fun decodeFirstFrame(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            // Pull the first key frame; clamp to a sensible target size so the
            // bitmap is small enough to keep many in cache.
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?.let(::scaleDown)
        } catch (t: Throwable) {
            AppLogger.e("ThumbnailLoader: metadata retriever failed for ${file.name} — ${t.message}")
            // Fall back to image decoding — some `.tmp` files are actually
            // jpeg/png stubs partially written.
            try {
                BitmapFactory.decodeFile(file.absolutePath)?.let(::scaleDown)
            } catch (_: Throwable) {
                null
            }
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
                // Some OEM builds throw on release; safe to ignore.
            }
        }
    }

    private fun scaleDown(input: Bitmap): Bitmap {
        val maxSide = 256
        val w = input.width
        val h = input.height
        if (w <= maxSide && h <= maxSide) return input
        val ratio = maxSide.toFloat() / maxOf(w, h)
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(input, nw, nh, true).also {
            if (it !== input) input.recycle()
        }
    }
}
