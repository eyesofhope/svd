package com.myAllVideoBrowser.ui.component.adapter

import android.content.Context
import android.graphics.Color
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.color.MaterialColors
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.model.LocalVideo
import com.myAllVideoBrowser.databinding.ItemVideoBinding
import com.myAllVideoBrowser.util.FileUtil

class VideoAdapter(
    private var localVideos: List<LocalVideo>,
    private val videoListener: VideoListener,
    private val fileUtil: FileUtil
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    /** Selected video URIs. Keyed by uri-string so it survives the 1s polling refresh. */
    private val selectedUris: MutableSet<String> = LinkedHashSet()

    /** When true the rows flip into select mode (check shown, taps toggle selection). */
    var isSelectionMode: Boolean = false
        private set

    val selectedCount: Int get() = selectedUris.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = DataBindingUtil.inflate<ItemVideoBinding>(
            LayoutInflater.from(parent.context), R.layout.item_video, parent, false
        )

        return VideoViewHolder(binding, fileUtil)
    }

    override fun getItemCount() = localVideos.size

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val item = localVideos[position]
        holder.bind(
            item,
            videoListener,
            isSelectionMode = isSelectionMode,
            isSelected = selectedUris.contains(item.uri.toString()),
        )
    }

    fun setData(localVideos: List<LocalVideo>) {
        // Drop selections for videos that no longer exist so the action-mode
        // count stays accurate across the view-model's 1-second refresh.
        val newUris = localVideos.map { it.uri.toString() }.toHashSet()
        selectedUris.retainAll(newUris)

        this.localVideos = localVideos
        notifyDataSetChanged()
    }

    // region selection -----------------------------------------------------------------

    fun enterSelectionMode(initial: LocalVideo) {
        isSelectionMode = true
        selectedUris.clear()
        selectedUris += initial.uri.toString()
        notifyDataSetChanged()
    }

    fun exitSelectionMode() {
        if (!isSelectionMode && selectedUris.isEmpty()) return
        isSelectionMode = false
        selectedUris.clear()
        notifyDataSetChanged()
    }

    fun toggleSelection(video: LocalVideo): Int {
        val key = video.uri.toString()
        if (selectedUris.contains(key)) selectedUris -= key
        else selectedUris += key
        notifyDataSetChanged()
        return selectedUris.size
    }

    fun selectAll() {
        selectedUris.clear()
        selectedUris += localVideos.map { it.uri.toString() }
        notifyDataSetChanged()
    }

    fun selectedVideos(): List<LocalVideo> {
        return localVideos.filter { selectedUris.contains(it.uri.toString()) }
    }

    // endregion ------------------------------------------------------------------------

    class VideoViewHolder(var binding: ItemVideoBinding, var fileUtil: FileUtil) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            localVideo: LocalVideo,
            videoListener: VideoListener,
            isSelectionMode: Boolean,
            isSelected: Boolean,
        ) {
            val ctx = itemView.context
            val size = getScreenResolution(ctx)
            val baseColor =
                MaterialColors.getColor(ctx, R.attr.colorSurfaceVariant, Color.YELLOW)

            with(binding) {
                this.localVideo = localVideo
                this.videoListener = videoListener

                Glide.with(this@VideoViewHolder.itemView.context).load(localVideo.uri).fitCenter()
                    .error(R.drawable.ic_video_24dp)
                    .placeholder(R.drawable.ic_video_24dp)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .apply(RequestOptions().override(size.first / 8, size.second / 8))
                    .into(this.ivThumbnail)

                // Selection chrome ------------------------------------------------------
                ivCheck.visibility =
                    if (isSelectionMode && isSelected) View.VISIBLE else View.GONE
                val selectedColor = ContextCompat.getColor(ctx, R.color.brand_accent_soft)
                cardVideo.setCardBackgroundColor(
                    if (isSelectionMode && isSelected) selectedColor else baseColor
                )
                cardVideo.strokeWidth = if (isSelectionMode && isSelected) 3 else 0
                cardVideo.strokeColor = ContextCompat.getColor(ctx, R.color.brand_accent)

                // While selecting, the overflow menu would compete with the
                // tap-to-toggle gesture, so hide it.
                ivMore.visibility = if (isSelectionMode) View.INVISIBLE else View.VISIBLE

                // Clicks are handled here (not in XML) so they can route to
                // selection toggling while in selection mode.
                cardVideo.setOnClickListener {
                    if (isSelectionMode) {
                        videoListener.onItemTapped(localVideo)
                    } else {
                        videoListener.onItemClicked(localVideo)
                    }
                }
                cardVideo.setOnLongClickListener {
                    videoListener.onItemLongPressed(localVideo)
                    true
                }

                executePendingBindings()
            }
        }

        private fun getScreenResolution(context: Context): Pair<Int, Int> {
            val displayMetrics = DisplayMetrics()
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            windowManager.defaultDisplay.getMetrics(displayMetrics)

            val widthPixels = displayMetrics.widthPixels
            val heightPixels = displayMetrics.heightPixels

            return Pair(widthPixels, heightPixels)
        }
    }
}

interface VideoListener {
    fun onItemClicked(localVideo: LocalVideo)
    fun onMenuClicked(view: View, localVideo: LocalVideo)

    /** Long-press on a card — enters multi-select mode. */
    fun onItemLongPressed(localVideo: LocalVideo) {}

    /** Single tap while already in multi-select mode. */
    fun onItemTapped(localVideo: LocalVideo) {}
}

@GlideModule
class MyGlideModule : AppGlideModule() {
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        builder.setLogLevel(Log.ERROR)
    }
}
