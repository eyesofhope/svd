package com.myAllVideoBrowser.ui.component.binding

import androidx.databinding.BindingAdapter
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.RequestOptions
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo

object ImageBinding {

    @BindingAdapter("app:imageUrl")
    @JvmStatic
    fun ImageView.loadImage(url: String?) {
        // Guard against blank URLs: many detected videos (HLS / MPD streams in
        // particular) carry no poster, and handing Glide an empty string just
        // produces a "Load failed for []" error and leaves the view undefined.
        if (url.isNullOrBlank()) {
            setImageResource(R.drawable.ic_video_24dp)
            return
        }
        Glide.with(context)
            .load(url)
            .placeholder(R.drawable.ic_video_24dp)
            .error(R.drawable.ic_video_24dp)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(this)
    }

    /**
     * Loads a detected video's poster image.
     *
     * Improvements over the plain [loadImage] path that matter for every user:
     *  - Skips the network call entirely when no poster URL exists, so the
     *    layout's placeholder overlay is the single source of truth and Glide
     *    never logs a spurious "Load failed for []".
     *  - Replays the same HTTP headers (Referer / User-Agent / Cookie) the
     *    detector captured for the media. Most CDNs that host adult / streaming
     *    posters answer a header-less request with 403, which is why a present
     *    thumbnail URL would otherwise still render blank.
     */
    @BindingAdapter("app:videoThumbnail")
    @JvmStatic
    fun ImageView.loadVideoThumbnail(videoInfo: VideoInfo?) {
        val url = videoInfo?.thumbnail
        if (videoInfo == null || url.isNullOrBlank()) {
            // The placeholder overlay (bound to thumbnail.isEmpty()) covers
            // this case; keep a sane image here too in case it is reused.
            setImageResource(R.drawable.ic_video_24dp)
            return
        }

        // Pull the headers the detector stored for this media so CDN-protected
        // posters load instead of 403'ing.
        val headers = videoInfo.formats.formats.firstOrNull()?.httpHeaders.orEmpty()
        val model: Any = if (headers.isEmpty()) {
            url
        } else {
            val builder = LazyHeaders.Builder()
            headers.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) builder.addHeader(name, value)
            }
            // Fall back to the page URL as Referer when the media headers omit
            // one — common for separately-served poster images.
            if (headers.keys.none { it.equals("Referer", ignoreCase = true) } &&
                videoInfo.originalUrl.startsWith("http")
            ) {
                builder.addHeader("Referer", videoInfo.originalUrl)
            }
            GlideUrl(url, builder.build())
        }

        Glide.with(context)
            .load(model)
            .placeholder(R.drawable.ic_video_24dp)
            .error(R.drawable.ic_video_24dp)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(this)
    }

    @BindingAdapter("app:bitmap")
    @JvmStatic
    fun ImageView.setImageBitmap(bitmap: Bitmap?) {
        bitmap?.let { setImageBitmap(it) }
    }

    @BindingAdapter("android:src")
    @JvmStatic
    fun setImageUri(view: ImageView, imageUri: String?) {
        if (imageUri == null) {
            view.setImageURI(null)
        } else {
            view.setImageURI(Uri.parse(imageUri))
        }
    }

    @BindingAdapter("android:src")
    @JvmStatic
    fun setImageUri(view: ImageView, imageUri: Uri?) {
        view.setImageURI(imageUri)
    }

    @BindingAdapter("android:src")
    @JvmStatic
    fun setImageDrawable(view: ImageView, drawable: Drawable?) {
        view.setImageDrawable(drawable)
    }

    @BindingAdapter("android:src")
    @JvmStatic
    fun setImageResource(imageView: ImageView, resource: Int) {
        imageView.setImageResource(resource)
    }
}
