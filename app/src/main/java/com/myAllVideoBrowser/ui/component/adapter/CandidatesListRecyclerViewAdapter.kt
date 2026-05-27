package com.myAllVideoBrowser.ui.component.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.ObservableField
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.databinding.DownloadCandidateItemBinding
import com.myAllVideoBrowser.util.FileUtil


interface DownloadVideoListener {
    fun onPreviewVideo(
        videoInfo: VideoInfo, dialog: BottomSheetDialog?, format: String, isForce: Boolean
    )

    fun onDownloadVideo(
        videoInfo: VideoInfo, dialog: BottomSheetDialog?, format: String, videoTitle: String
    )
}

interface DownloadTabVideoListener {
    fun onPreviewVideo(
        videoInfo: VideoInfo, format: String, isForce: Boolean
    )

    fun onDownloadVideo(
        videoInfo: VideoInfo, format: String, videoTitle: String
    )
}

interface DownloadDialogListener : DownloadVideoListener, CandidateFormatListener {
    fun onCancel(dialog: BottomSheetDialog?)
}

interface DownloadTabListener : DownloadTabVideoListener, CandidateFormatListener {
    fun onCancel()
}

interface CandidateFormatListener {
    fun onSelectFormat(videoInfo: VideoInfo, format: String)

    fun onFormatUrlShare(videoInfo: VideoInfo, format: String): Boolean
}

class CandidatesListRecyclerViewAdapter(
    private val downloadCandidates: VideoInfo,
    private val selectedFormat: ObservableField<Map<String, String>>,
    private val downloadDialogListener: CandidateFormatListener
) : RecyclerView.Adapter<CandidatesListRecyclerViewAdapter.CandidatesViewHolder>() {

    private var allFormats: List<VideoFormatEntity> = arrayListOf()
    private var formats: List<VideoFormatEntity> = arrayListOf()
    private var audioOnlyMode: Boolean = false

    init {
        allFormats = getShortenFormats(downloadCandidates.formats.formats)
        formats = filterByMode(allFormats, audioOnlyMode)
    }

    class CandidatesViewHolder(val binding: DownloadCandidateItemBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidatesViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = DownloadCandidateItemBinding.inflate(inflater, parent, false)
        return CandidatesViewHolder(binding)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: CandidatesViewHolder, position: Int) {
        val formatEntity = formats[position]
        val candidate = formatEntity.format ?: "error"

        with(holder.binding) {
            val selected = selectedFormat.get()?.get(downloadCandidates.id)

            this.videoInfo = downloadCandidates
            this.downloadCandidate = candidate
            this.isCandidateSelected = candidate == selected
            this.tvTitle.text = getShortOfFormat(candidate, downloadCandidates.isDetectedBySuperX)

            this.listener = object : CandidateFormatListener {
                override fun onSelectFormat(videoInfo: VideoInfo, format: String) {
                    val currentPosition = holder.bindingAdapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        downloadDialogListener.onSelectFormat(videoInfo, format)
                        notifyDataSetChanged()
                    }
                }

                override fun onFormatUrlShare(videoInfo: VideoInfo, format: String): Boolean {
                    val currentPosition = holder.bindingAdapterPosition
                    return if (currentPosition != RecyclerView.NO_POSITION) {
                        downloadDialogListener.onFormatUrlShare(videoInfo, format)
                    } else {
                        false
                    }
                }
            }

            val formatSize = if (formatEntity.fileSizeApproximate > 0) {
                FileUtil.getFileSizeReadable(formatEntity.fileSizeApproximate.toDouble())
            } else if (formatEntity.fileSize > 0) {
                FileUtil.getFileSizeReadable(formatEntity.fileSize.toDouble())
            } else {
                "Unknown"
            }

            this.tvData.text = formatSize

            this.executePendingBindings()
        }
    }

    override fun getItemCount(): Int = formats.size

    fun setData(formats: List<VideoFormatEntity>) {
        this.allFormats = formats
        this.formats = filterByMode(allFormats, audioOnlyMode)
        notifyDataSetChanged()
    }

    /**
     * Refreshes the displayed formats. The optional [video] parameter is unused
     * but kept for future per-video metadata refresh from callers.
     */
    fun setData(formats: List<VideoFormatEntity>, video: VideoInfo) {
        @Suppress("UNUSED_PARAMETER") video
        this.allFormats = formats
        // When the caller supplies a pre-filtered list (e.g., the on-demand
        // audio resolution result) we want to show it as-is rather than running
        // the heuristic filter again.
        this.formats = formats
        notifyDataSetChanged()
    }

    /**
     * Switch the list between video formats and audio-only formats.
     * Returns the list of currently visible formats so the caller can update
     * the selected format if needed.
     */
    fun setAudioOnly(isAudioOnly: Boolean): List<VideoFormatEntity> {
        if (audioOnlyMode == isAudioOnly) return formats
        audioOnlyMode = isAudioOnly
        formats = filterByMode(allFormats, audioOnlyMode)
        notifyDataSetChanged()
        return formats
    }

    fun isAudioOnly(): Boolean = audioOnlyMode

    fun hasAudioFormats(): Boolean = filterByMode(allFormats, true).isNotEmpty()
    fun hasVideoFormats(): Boolean = filterByMode(allFormats, false).isNotEmpty()

    fun visibleFormats(): List<VideoFormatEntity> = formats

    private fun filterByMode(
        source: List<VideoFormatEntity>, audioOnly: Boolean
    ): List<VideoFormatEntity> {
        if (source.isEmpty()) return source
        val audio = source.filter { isAudioOnlyFormat(it) }
        // If there are no clearly classified audio formats, fall back to showing
        // everything so the toggle never produces an empty list when only one
        // type is available.
        return if (audioOnly) {
            if (audio.isNotEmpty()) audio else source
        } else {
            val video = source.filterNot { isAudioOnlyFormat(it) }
            if (video.isNotEmpty()) video else source
        }
    }

    private fun isAudioOnlyFormat(format: VideoFormatEntity): Boolean {
        val vcodec = format.vcodec?.lowercase().orEmpty()
        val acodec = format.acodec?.lowercase().orEmpty()
        val fmt = format.format?.lowercase().orEmpty()
        val fmtId = format.formatId?.lowercase().orEmpty()
        val ext = format.ext?.lowercase().orEmpty()

        val noVideo = vcodec.isEmpty() || vcodec == "none" || vcodec == "unknown"
        val hasAudio = acodec.isNotEmpty() && acodec != "none" && acodec != "unknown"

        if (fmt.contains("audio only") || fmt.contains("audio")) return true
        if (fmtId.contains("audio")) return true
        if (ext in listOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")) return true

        // Sometimes vcodec is unknown; lean on width/height being 0 plus an audio codec.
        if (format.width == 0 && format.height == 0 && hasAudio && noVideo) return true

        return noVideo && hasAudio
    }

    private fun makeVideoFormatHumanReadable(input: String, isDetectedBySuperX: Boolean): String {
        val lowercasedInput = input.lowercase()
        return when {
            isDetectedBySuperX && (lowercasedInput.startsWith("mpd-") || lowercasedInput.startsWith(
                "hls-"
            )) -> {
                // Drop the manifest prefix (HLS/MPD) — users only care about the
                // resolution, not whether it came from an HLS or MPEG-DASH stream.
                val parts = lowercasedInput.split('-')
                if (parts.size >= 2) {
                    val resolution = parts[1] // "1080p" or "audio"
                    if (resolution.contains("p")) {
                        resolution.uppercase()
                    } else {
                        ""
                    }
                } else {
                    input
                }
            }

            else -> input.replace(Regex("-\\w+"), "")
        }
    }

    private fun getShortenFormats(allFormats: List<VideoFormatEntity>): List<VideoFormatEntity> {
        val formatsMap = mutableMapOf<String, VideoFormatEntity>()
        for (format in allFormats) {
            formatsMap[format.format.toString()] = format
        }

        formatsMap.remove("")

        formatsMap.toSortedMap()

        return formatsMap.toSortedMap().values.toList().sortedBy { it.formatNote }
    }

    private fun getShortOfFormat(format: String?, detectedBySuperX: Boolean): String {
        val formattedFormat = makeVideoFormatHumanReadable(format ?: "error", detectedBySuperX)
        if (formattedFormat != "error") {
            return if (formattedFormat.contains("x")) {
                "${parseHeight(formattedFormat)}P"
            } else if (!formattedFormat.contains("x") && !formattedFormat.contains("audio only") && formattedFormat.contains(
                    "-"
                )
            ) {
                val leftSide = formattedFormat.split("-").first()
                if (leftSide.lowercase().contains("hd") || leftSide.contains("sd")) {
                    return leftSide.trim()
                }
                val rightSide = formattedFormat.split("-").last()
                rightSide.replace("p", "P").trim()
            } else if (formattedFormat.contains("audio only")) {
                "Audio"
            } else {
                formattedFormat
            }
        }

        return "Error"
    }

    private fun parseHeight(input: String): Int? {
        val regex = Regex("""\d+x(\d+)""")
        val matchResult = regex.find(input)

        return if (matchResult != null) {
            matchResult.groupValues[1].toIntOrNull()
        } else {
            null
        }
    }
}
