import com.myAllVideoBrowser.ui.component.adapter.CandidatesListRecyclerViewAdapter
import com.myAllVideoBrowser.ui.component.adapter.DownloadTabListener
import android.annotation.SuppressLint
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.databinding.DataBindingUtil
import androidx.databinding.Observable
import androidx.databinding.Observable.OnPropertyChangedCallback
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.databinding.ItemVideoInfoBinding
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.VideoDetectionTabViewModel
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.FileUtil

class VideoInfoAdapter(
    private var videoInfoList: List<VideoInfo>,
    private val model: VideoDetectionTabViewModel,
    private val downloadVideoListener: DownloadTabListener,
    private val appUtil: AppUtil
) :
    RecyclerView.Adapter<VideoInfoAdapter.VideoInfoViewHolder>() {

    class VideoInfoViewHolder(
        val binding: ItemVideoInfoBinding,
        val model: VideoDetectionTabViewModel,
        private val candidateFormatListener: DownloadTabListener,
        private val appUtil: AppUtil
    ) :
        RecyclerView.ViewHolder(binding.root) {
        private val selectedFormatsCallback = object : OnPropertyChangedCallback() {
            override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                val currentVideoInfo = binding.videoInfo ?: return

                val curSelected = model.selectedFormats.get()?.get(currentVideoInfo.id)
                val foundFormat = currentVideoInfo.formats.formats.find { it.format == curSelected }

                if (foundFormat != null) {
                    model.selectedFormatUrl.set(foundFormat.url)
                }
            }
        }

        private val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val title = s.toString()
                binding.videoInfo?.id?.let { videoId ->
                    val titlesF = model.formatsTitles.get()?.toMutableMap() ?: mutableMapOf()
                    titlesF[videoId] = title
                    model.formatsTitles.set(titlesF)
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        }
        private var isCallbackAdded = false

        @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
        fun bind(info: VideoInfo) {
            with(binding) {
                videoTitleEdit.removeTextChangedListener(textWatcher)

                val titles = model.formatsTitles.get()?.toMutableMap() ?: mutableMapOf()
                titles[info.id] = titles[info.id] ?: info.title
                model.formatsTitles.set(titles)

                val frmts = model.selectedFormats.get()?.toMutableMap() ?: mutableMapOf()
                val selected = frmts[info.id]
                val defaultFormat = info.formats.formats.lastOrNull()?.format ?: "unknown"
                if (selected == null) {
                    frmts[info.id] = defaultFormat
                }
                model.selectedFormats.set(frmts)

                if (info.isRegularDownload) {
                    model.selectedFormatUrl.set(info.firstUrlToString)
                } else {
                    model.selectedFormatUrl.set(info.formats.formats.lastOrNull()?.url)
                }

                videoInfo = info
                val typeText = if (info.isM3u8 || info.isMpd) {
                    val isMpd = info.formats.formats.firstOrNull()?.isMpd == true
                    if (isMpd) "MPD List" else "M3U8 List"
                } else if (info.isMaster) {
                    val isMpd = info.formats.formats.firstOrNull()?.isMpd == true
                    if (isMpd) "MPD Master List" else "M3U8 Master List"
                } else if (info.isRegularDownload) {
                    "Regular MP4 Download"
                } else {
                    ""
                }

                if (info.isRegularDownload) {
                    val fileSize = info.formats.formats.firstOrNull()?.fileSize
                    if (fileSize != null && fileSize > 0) {
                        val size = FileUtil.getFileSizeReadable(fileSize.toDouble())
                        sizeTextView.text = "Download Size: $size"
                        sizeTextView.visibility = View.VISIBLE
                    } else {
                        sizeTextView.visibility = View.GONE
                    }
                } else {
                    sizeTextView.visibility = View.GONE
                }
                typeTextView.text = typeText
                typeTextView.visibility =
                    if (typeText.isNotEmpty()) View.VISIBLE else View.GONE

                actionRename.setOnClickListener {
                    videoTitleEdit.requestFocus()
                    this.videoTitleEdit.selectAll()
                    appUtil.showSoftKeyboard(videoTitleEdit)
                }

                this.videoTitleEdit.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        this.videoTitleEdit.clearFocus()
                        appUtil.hideSoftKeyboard(videoTitleEdit)
                        false
                    } else false
                }

                videoTitleEdit.setText(titles[info.id])

                viewModel = model

                val candidatesAdapter = CandidatesListRecyclerViewAdapter(
                    info,
                    model.selectedFormats,
                    candidateFormatListener
                )

                val gridLayoutManager = GridLayoutManager(binding.root.context, 3)
                candidatesList.layoutManager = gridLayoutManager
                candidatesList.adapter = candidatesAdapter

                // Configure the Video / Audio toggle
                val hasAudio = candidatesAdapter.hasAudioFormats()
                val hasVideo = candidatesAdapter.hasVideoFormats()

                // Hide the toggle entirely if only one media type is available
                mediaTypeToggle.visibility =
                    if (hasAudio && hasVideo) View.VISIBLE else View.GONE

                // Avoid stale listeners on view recycle
                mediaTypeToggle.clearOnButtonCheckedListeners()
                mediaTypeToggle.check(R.id.btn_filter_video)

                mediaTypeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
                    if (!isChecked) return@addOnButtonCheckedListener
                    val audioOnly = checkedId == R.id.btn_filter_audio
                    val visible = candidatesAdapter.setAudioOnly(audioOnly)
                    val firstFormat = visible.firstOrNull()?.format
                    if (firstFormat != null) {
                        candidateFormatListener.onSelectFormat(info, firstFormat)
                    }
                    tvNoFormats.visibility =
                        if (visible.isEmpty()) View.VISIBLE else View.GONE
                    candidatesList.visibility =
                        if (visible.isEmpty()) View.GONE else View.VISIBLE
                }

                tvNoFormats.visibility =
                    if (candidatesAdapter.visibleFormats().isEmpty()) View.VISIBLE else View.GONE
                candidatesList.visibility =
                    if (candidatesAdapter.visibleFormats().isEmpty()) View.GONE else View.VISIBLE

                dialogListener = object : DownloadTabListener {
                    override fun onCancel() {
                        candidateFormatListener.onCancel()
                    }

                    override fun onPreviewVideo(
                        videoInfo: VideoInfo,
                        format: String,
                        isForce: Boolean
                    ) {
                        candidateFormatListener.onPreviewVideo(videoInfo, format, isForce)
                    }

                    override fun onFormatUrlShare(videoInfo: VideoInfo, format: String): Boolean {
                        return candidateFormatListener.onFormatUrlShare(videoInfo, format)
                    }

                    override fun onDownloadVideo(
                        videoInfo: VideoInfo,
                        format: String,
                        videoTitle: String
                    ) {
                        val text = model.formatsTitles.get()?.get(videoInfo.id)
                        if (text != null) {
                            candidateFormatListener.onDownloadVideo(videoInfo, format, text)
                        }
                    }

                    override fun onSelectFormat(videoInfo: VideoInfo, format: String) {
                        candidateFormatListener.onSelectFormat(videoInfo, format)
                    }
                }

                videoTitleEdit.addTextChangedListener(textWatcher)

                executePendingBindings()
            }
        }

        fun addCallback() {
            if (!isCallbackAdded) {
                model.selectedFormats.addOnPropertyChangedCallback(selectedFormatsCallback)
                isCallbackAdded = true
            }
        }

        fun removeCallback() {
            if (isCallbackAdded) {
                model.selectedFormats.removeOnPropertyChangedCallback(selectedFormatsCallback)
                isCallbackAdded = false
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoInfoViewHolder {
        val binding = DataBindingUtil.inflate<ItemVideoInfoBinding>(
            LayoutInflater.from(parent.context),
            R.layout.item_video_info,
            parent,
            false
        )

        return VideoInfoViewHolder(binding, model, downloadVideoListener, appUtil)
    }

    override fun onBindViewHolder(holder: VideoInfoViewHolder, position: Int) {
        val videoInfo = videoInfoList[position]
        holder.bind(videoInfo)
    }

    override fun onViewAttachedToWindow(holder: VideoInfoViewHolder) {
        super.onViewAttachedToWindow(holder)
        holder.addCallback()
    }

    override fun onViewDetachedFromWindow(holder: VideoInfoViewHolder) {
        super.onViewDetachedFromWindow(holder)
        holder.removeCallback()
    }


    override fun getItemCount(): Int = videoInfoList.size

    fun setData(localVideos: List<VideoInfo>) {
        this.videoInfoList = localVideos.reversed()
        notifyDataSetChanged()
    }
}
