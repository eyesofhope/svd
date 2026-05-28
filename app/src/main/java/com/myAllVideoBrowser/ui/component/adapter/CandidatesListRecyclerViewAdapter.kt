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

            this.tvData.text = formatSizeText(formatEntity)

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
        // Bucket formats by a normalised resolution key so duplicates from
        // different sources (SuperX HLS detector vs yt-dlp) and different
        // codec variants (av01/vp9/avc1, DASH vs muxed) collapse to one row
        // per quality. Non-downloadable formats (storyboards, MHTML thumbs,
        // anything without both video and audio codecs) are dropped entirely.
        val grouped = LinkedHashMap<String, VideoFormatEntity>()
        for (format in allFormats) {
            val raw = format.format
            if (raw.isNullOrBlank()) continue
            if (isNonMediaFormat(format)) continue

            val label = getShortOfFormat(raw, downloadCandidates.isDetectedBySuperX)
            if (label == "Error") continue

            val key = dedupeKey(format, label)
            val existing = grouped[key]
            if (existing == null || isBetterCandidate(format, existing)) {
                grouped[key] = format
            }
        }

        return grouped.values.sortedWith(
            compareBy<VideoFormatEntity> { isAudioOnlyFormat(it) }
                .thenByDescending { it.height }
                .thenByDescending { it.tbr }
        )
    }

    /**
     * Drops storyboard / preview / thumbnail-only entries that yt-dlp
     * sometimes returns alongside real media. These are not playable video
     * (no audio AND no video codec, MHTML container, or "storyboard" hint
     * in the raw format string) and would otherwise show up as bogus rows
     * in the download dialog.
     */
    private fun isNonMediaFormat(format: VideoFormatEntity): Boolean {
        val ext = format.ext?.lowercase().orEmpty()
        if (ext == "mhtml") return true

        val raw = format.format?.lowercase().orEmpty()
        val note = format.formatNote?.lowercase().orEmpty()
        if (raw.contains("storyboard") || note.contains("storyboard")) return true

        // sb0 / sb1 / sb2 / sb3 are YouTube's storyboard format ids.
        val fmtId = format.formatId?.lowercase().orEmpty()
        if (fmtId.matches(Regex("^sb\\d+$"))) return true

        val vcodec = format.vcodec?.lowercase().orEmpty()
        val acodec = format.acodec?.lowercase().orEmpty()
        val noVideo = vcodec.isEmpty() || vcodec == "none" || vcodec == "unknown"
        val noAudio = acodec.isEmpty() || acodec == "none" || acodec == "unknown"
        // Both codecs missing AND no resolution → cannot be played as media.
        if (noVideo && noAudio && format.width == 0 && format.height == 0) return true

        return false
    }

    /**
     * Resolution-based dedupe key. Two formats with different display labels
     * (e.g. SuperX `hls-1080p-6398882` vs yt-dlp `1080p - 1920x1080`) should
     * still collapse to one row when they describe the same quality. Falls
     * back to the visible label when no usable resolution data exists.
     */
    private fun dedupeKey(format: VideoFormatEntity, label: String): String {
        if (isAudioOnlyFormat(format)) return "audio"

        val height = when {
            format.height > 0 -> format.height
            else -> {
                // Try the visible label ("1080P", "720P", ...).
                val labelMatch = Regex("(\\d{2,4})\\s*[pP]").find(label)
                labelMatch?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(\\d{2,4})").find(label)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 0
            }
        }
        return if (height > 0) "video:${height}p" else "video:${label.lowercase()}"
    }

    /**
     * Picks the more useful variant when two formats collapse to the same
     * display label. Muxed (audio + video) wins over split tracks; otherwise
     * the larger file / higher bitrate wins.
     */
    private fun isBetterCandidate(
        candidate: VideoFormatEntity, current: VideoFormatEntity
    ): Boolean {
        val candidateMuxed = isMuxed(candidate)
        val currentMuxed = isMuxed(current)
        if (candidateMuxed != currentMuxed) return candidateMuxed

        val candidateSize = candidate.fileSizeApproximate.takeIf { it > 0 } ?: candidate.fileSize
        val currentSize = current.fileSizeApproximate.takeIf { it > 0 } ?: current.fileSize
        if (candidateSize != currentSize) return candidateSize > currentSize

        return candidate.tbr > current.tbr
    }

    private fun isMuxed(format: VideoFormatEntity): Boolean {
        val vcodec = format.vcodec?.lowercase().orEmpty()
        val acodec = format.acodec?.lowercase().orEmpty()
        val hasVideo = vcodec.isNotEmpty() && vcodec != "none" && vcodec != "unknown"
        val hasAudio = acodec.isNotEmpty() && acodec != "none" && acodec != "unknown"
        return hasVideo && hasAudio
    }

    /**
     * Resolves the size string for a row. Prefers the exact byte count, then
     * yt-dlp's approximate count, and finally estimates from total bitrate
     * (kbps) × video duration when both are known. HLS variants and many
     * DASH streams ship without a fileSize, so this estimate is the only
     * thing standing between the user and a row that just says "Unknown".
     */
    private fun formatSizeText(format: VideoFormatEntity): String {
        val exact = format.fileSizeApproximate.takeIf { it > 0 }
            ?: format.fileSize.takeIf { it > 0 }
        if (exact != null) {
            return FileUtil.getFileSizeReadable(exact.toDouble())
        }

        val durationMs = format.duration?.takeIf { it > 0 }
            ?: downloadCandidates.duration.takeIf { it > 0 }
            ?: return "Unknown"

        val tbrKbps = format.tbr.takeIf { it > 0 } ?: return "Unknown"

        // tbr is total bitrate in kbps. bytes ≈ kbps * 1000 / 8 * seconds.
        // duration is stored in milliseconds (yt-dlp seconds × 1000 from
        // the conversion layer); guard against either unit.
        val durationSeconds = if (durationMs > 36_000) durationMs / 1000.0 else durationMs.toDouble()
        val approxBytes = (tbrKbps.toLong() * 1000L / 8L) * durationSeconds
        if (approxBytes <= 0) return "Unknown"
        return "~${FileUtil.getFileSizeReadable(approxBytes)}"
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
