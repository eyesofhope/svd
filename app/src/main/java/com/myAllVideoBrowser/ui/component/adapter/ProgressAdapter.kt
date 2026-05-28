package com.myAllVideoBrowser.ui.component.adapter

import android.content.Context
import android.graphics.Color
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.databinding.ItemProgressBinding
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState

/**
 * Adapter for the redesigned download-progress page.
 *
 * Responsibilities beyond a vanilla RecyclerView adapter:
 *  - Tracks live download speed per item (delta bytes / delta time).
 *  - Drives the canvas-based [com.myAllVideoBrowser.ui.component.widget.AnimatedDownloadButton]
 *    on the trailing button; its single source of truth is `progress` in `[0, 1]`.
 *  - Loads thumbnails through [ThumbnailLoader] which falls back to a frame
 *    extracted from the partially-downloaded file when no URL is available.
 *  - Surfaces multi-select state so the host fragment can drive an ActionMode.
 *  - Maps the per-row taps to discrete [ProgressListener] callbacks.
 */
class ProgressAdapter(
    private var progressInfos: List<ProgressInfo>,
    private var videoListener: ProgressListener,
) : RecyclerView.Adapter<ProgressAdapter.ProgressViewHolder>() {

    /** Selection state — a Set keeps the API simple and idempotent. */
    private val selectedIds: MutableSet<Long> = LinkedHashSet()

    /** When true the row chrome flips into select-mode (check shown, taps go to toggleSelection). */
    var isSelectionMode: Boolean = false
        private set

    val selectedCount: Int get() = selectedIds.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProgressViewHolder {
        val binding = DataBindingUtil.inflate<ItemProgressBinding>(
            LayoutInflater.from(parent.context), R.layout.item_progress, parent, false
        )
        return ProgressViewHolder(binding)
    }

    override fun getItemCount() = progressInfos.size

    override fun onBindViewHolder(holder: ProgressViewHolder, position: Int) {
        val item = progressInfos[position]
        holder.bind(
            item,
            videoListener,
            isSelectionMode = isSelectionMode,
            isSelected = selectedIds.contains(item.downloadId),
        )
    }

    override fun onViewRecycled(holder: ProgressViewHolder) {
        super.onViewRecycled(holder)
        holder.recycle()
    }

    fun setData(progressInfos: List<ProgressInfo>) {
        // Forget speed samples for items that are gone — keep memory bounded.
        val newIds = progressInfos.map { it.downloadId }.toHashSet()
        val removed = this.progressInfos.map { it.downloadId } - newIds
        removed.forEach { DownloadSpeedTracker.forget(it) }

        // Drop selections that no longer exist.
        selectedIds.retainAll(newIds)

        this.progressInfos = progressInfos
        notifyDataSetChanged()
    }

    // region selection -----------------------------------------------------------------

    fun enterSelectionMode(initialDownloadId: Long) {
        isSelectionMode = true
        selectedIds.clear()
        selectedIds += initialDownloadId
        notifyDataSetChanged()
    }

    fun exitSelectionMode() {
        if (!isSelectionMode && selectedIds.isEmpty()) return
        isSelectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
    }

    fun toggleSelection(downloadId: Long): Int {
        if (selectedIds.contains(downloadId)) selectedIds -= downloadId
        else selectedIds += downloadId
        // Cheap notify — full update keeps animations tidy and the list is short.
        notifyDataSetChanged()
        return selectedIds.size
    }

    fun selectAll() {
        selectedIds.clear()
        selectedIds += progressInfos.map { it.downloadId }
        notifyDataSetChanged()
    }

    fun selectedDownloadIds(): List<Long> = selectedIds.toList()

    // endregion ------------------------------------------------------------------------

    class ProgressViewHolder(val binding: ItemProgressBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            progressInfo: ProgressInfo,
            progressListener: ProgressListener,
            isSelectionMode: Boolean,
            isSelected: Boolean,
        ) {
            val ctx = itemView.context
            val size = getScreenResolution(ctx)
            val cardColor =
                MaterialColors.getColor(ctx, R.attr.colorSurfaceVariant, Color.YELLOW)

            with(binding) {
                this.progressInfo = progressInfo
                this.progressListener = progressListener
                this.downloadId = progressInfo.downloadId
                this.isRegular = progressInfo.videoInfo.isRegularDownload

                cardProgress.setCardBackgroundColor(cardColor)

                ThumbnailLoader.load(ivThumbnail, progressInfo, size.first, size.second)

                bindProgressAndSpeed(progressInfo)
                bindDownloadButton(progressInfo, progressListener)
                bindPlayButton(progressInfo)
                bindSelectionChrome(isSelectionMode, isSelected)

                // Row taps
                cardProgress.setOnClickListener {
                    if (isSelectionMode) {
                        progressListener.onItemTapped(progressInfo.downloadId)
                    } else {
                        // tapping the card behaves like the play preview action
                        progressListener.onPreviewClicked(progressInfo.downloadId)
                    }
                }
                cardProgress.setOnLongClickListener {
                    progressListener.onItemLongPressed(progressInfo.downloadId)
                    true
                }

                btnPlay.setOnClickListener {
                    progressListener.onPreviewClicked(progressInfo.downloadId)
                }
                btnClose.setOnClickListener {
                    progressListener.onRemoveClicked(progressInfo.downloadId)
                }

                executePendingBindings()
            }
        }

        /** Called from `onViewRecycled` to release any per-row resources. */
        fun recycle() {
            // The new button has identity-aware bind: leave its state alone so
            // a quick rebind to the same downloadId picks up where it left
            // off. We only do a full release when the view is actually
            // detached (the View itself handles `onDetachedFromWindow`).
        }

        // ------------------------------------------------------------------------------

        private fun bindProgressAndSpeed(info: ProgressInfo) {
            val total = info.progressTotal
            val done = info.progressDownloaded
            val percent = if (total > 0) ((done * 100f) / total).toInt().coerceIn(0, 100) else 0

            with(binding.progressBar) {
                isIndeterminate =
                    info.downloadStatus == VideoTaskState.PREPARE && total <= 0
                if (!isIndeterminate) setProgressCompat(percent, /* animated */ true)
            }

            // Status line: "12.82 MB / 38.06 MB · status"
            val sizeLabel = info.progressSize.substringBefore(" - ")
            val statusLabel = humanStatus(info)
            binding.tvStatus.text = "$sizeLabel · $statusLabel"

            // Speed line — only meaningful while actively downloading.
            val isMoving = info.downloadStatus == VideoTaskState.DOWNLOADING
            val speed = if (isMoving) DownloadSpeedTracker.update(info.downloadId, done) else null
            val speedText = DownloadSpeedTracker.format(speed)
            if (speedText != null) {
                binding.tvSpeed.visibility = View.VISIBLE
                // The user wants an upward-pointing arrow as the indicator
                // before the speed value (the previous "↓" was confusing on a
                // download line that already reads MB/s).
                binding.tvSpeed.text = "↑ $speedText"
            } else {
                binding.tvSpeed.visibility = View.GONE
            }
        }

        private fun humanStatus(info: ProgressInfo): String {
            val ctx = itemView.context
            return when (info.downloadStatus) {
                VideoTaskState.DOWNLOADING -> "downloading"
                VideoTaskState.PAUSE -> ctx.getString(R.string.progress_status_paused)
                VideoTaskState.PENDING -> ctx.getString(R.string.progress_status_pending)
                VideoTaskState.PREPARE -> ctx.getString(R.string.progress_status_waiting)
                VideoTaskState.START,
                VideoTaskState.PROXYREADY -> ctx.getString(R.string.progress_status_processing)
                VideoTaskState.ERROR, VideoTaskState.ENOSPC ->
                    ctx.getString(R.string.progress_status_failed)
                VideoTaskState.SUCCESS -> "completed"
                else -> info.downloadStatusFormatted
            }
        }

        private fun bindDownloadButton(
            info: ProgressInfo,
            listener: ProgressListener,
        ) {
            val total = info.progressTotal
            val done = info.progressDownloaded
            val percent = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f

            val isActive = when (info.downloadStatus) {
                VideoTaskState.DOWNLOADING,
                VideoTaskState.PREPARE,
                VideoTaskState.START,
                VideoTaskState.PROXYREADY,
                VideoTaskState.PENDING -> true
                else -> false
            }

            val mode = when {
                info.downloadStatus == VideoTaskState.SUCCESS ->
                    com.myAllVideoBrowser.ui.component.widget.AnimatedDownloadButton.Mode.DONE
                isActive ->
                    com.myAllVideoBrowser.ui.component.widget.AnimatedDownloadButton.Mode.ACTIVE
                info.downloadStatus == VideoTaskState.PAUSE ->
                    com.myAllVideoBrowser.ui.component.widget.AnimatedDownloadButton.Mode.PAUSED
                else ->
                    com.myAllVideoBrowser.ui.component.widget.AnimatedDownloadButton.Mode.IDLE
            }

            with(binding.btnDownload) {
                strokeColor = ContextCompat.getColor(itemView.context, R.color.brand_accent)
                // Identity-aware bind: same row + new progress = wave keeps
                // running smoothly. New row = button resets first.
                bind(info.downloadId, mode, percent)

                onClick = {
                    if (isActive) listener.onPauseClicked(info.downloadId)
                    else listener.onResumeClicked(info.downloadId)
                }
            }
        }

        /**
         * The play button is only meaningful once the file is fully downloaded.
         * Until then we render it dimmed and ignore taps so users don't try
         * to preview a partial file.
         */
        private fun bindPlayButton(info: ProgressInfo) {
            val isReady = info.downloadStatus == VideoTaskState.SUCCESS
            with(binding.btnPlay) {
                isEnabled = isReady
                isClickable = isReady
                isFocusable = isReady
                alpha = if (isReady) 1f else 0.35f
            }
        }

        private fun bindSelectionChrome(isSelectionMode: Boolean, isSelected: Boolean) {
            binding.ivCheck.visibility =
                if (isSelectionMode && isSelected) View.VISIBLE else View.GONE
            // MaterialCardView ignores state-list backgrounds when a stroke + corner
            // radius are set, so we apply the selection color directly.
            val ctx = itemView.context
            val baseColor =
                MaterialColors.getColor(ctx, R.attr.colorSurfaceVariant, Color.YELLOW)
            val selectedColor = ContextCompat.getColor(ctx, R.color.brand_accent_soft)
            binding.cardProgress.setCardBackgroundColor(
                if (isSelectionMode && isSelected) selectedColor else baseColor
            )
            binding.cardProgress.strokeWidth = if (isSelectionMode && isSelected) 3 else 0
            binding.cardProgress.strokeColor =
                ContextCompat.getColor(ctx, R.color.brand_accent)
            // While in selection mode the action buttons would compete with the
            // tap-to-toggle gesture, so dim them and disable. The play button
            // already maintains its own enabled/alpha state via
            // [bindPlayButton] (it stays faded until the download is SUCCESS),
            // so only force-dim it when entering selection mode.
            val enabled = !isSelectionMode
            if (isSelectionMode) {
                binding.btnPlay.isEnabled = false
                binding.btnPlay.alpha = 0.35f
            }
            binding.btnDownload.isEnabled = enabled
            binding.btnClose.isEnabled = enabled
            val alpha = if (enabled) 1f else 0.35f
            binding.btnDownload.alpha = alpha
            binding.btnClose.alpha = alpha
        }

        private fun getScreenResolution(context: Context): Pair<Int, Int> {
            val displayMetrics = DisplayMetrics()
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            return Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
        }
    }
}

/**
 * Callbacks fired by the redesigned progress row. Implemented by the fragment.
 *
 * The legacy [onMenuClicked] is kept for binary compatibility with any other
 * call sites or generated code that may still reference it; it is no longer
 * used by the new layout.
 */
interface ProgressListener {
    /** Trailing × on the card. The fragment is expected to confirm before deleting. */
    fun onRemoveClicked(downloadId: Long)

    /** Animated download icon while a download is active. */
    fun onPauseClicked(downloadId: Long)

    /** Animated download icon while a download is paused / failed. */
    fun onResumeClicked(downloadId: Long)

    /** Filled play-circle button (replaces the previous globe icon). */
    fun onPreviewClicked(downloadId: Long)

    /** Long-press on the card — used to enter multi-select mode. */
    fun onItemLongPressed(downloadId: Long)

    /** Single tap while already in multi-select mode. */
    fun onItemTapped(downloadId: Long)

    /** Legacy hook — retained for compatibility, currently a no-op. */
    fun onMenuClicked(view: View, downloadId: Long, isRegular: Boolean) {}
}
