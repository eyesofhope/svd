package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import VideoInfoAdapter
import android.app.Dialog
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentDetectedVideosTabBinding
import com.myAllVideoBrowser.ui.component.adapter.DownloadTabListener
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.progress.WrapContentLinearLayoutManager
import com.myAllVideoBrowser.util.AppUtil
import dagger.android.support.AndroidSupportInjection
import javax.inject.Inject

class DetectedVideosTabFragment : BottomSheetDialogFragment() {
    var detectedVideosTabViewModel: VideoDetectionTabViewModel? = null
    var candidateFormatListener: DownloadTabListener? = null

    @Inject
    lateinit var mainActivity: MainActivity

    @Inject
    lateinit var appUtil: AppUtil

    private lateinit var binding: FragmentDetectedVideosTabBinding

    private lateinit var layoutMngr: WrapContentLinearLayoutManager

    companion object {
        const val TAG = "DOWNLOADS_TAB"
        fun newInstance() = DetectedVideosTabFragment()
    }

    override fun onAttach(context: android.content.Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun getTheme(): Int = R.style.AppBottomSheetDialogTheme

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { dlg ->
            val bottomSheet = (dlg as BottomSheetDialog)
                .findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.background = ColorDrawable(Color.TRANSPARENT)

            BottomSheetBehavior.from(bottomSheet ?: return@setOnShowListener).apply {
                // Open most of the way; user can drag up to expand or down to dismiss
                val maxHeight = (Resources.getSystem().displayMetrics.heightPixels * 0.92).toInt()
                bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                peekHeight = (Resources.getSystem().displayMetrics.heightPixels * 0.7).toInt()
                isFitToContents = true
                skipCollapsed = false
                isHideable = true
                state = BottomSheetBehavior.STATE_EXPANDED
                this.maxHeight = maxHeight
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        if (detectedVideosTabViewModel == null || candidateFormatListener == null) {
            Toast.makeText(context, "Something went wrong, try again.", Toast.LENGTH_SHORT).show()
            dismissAllowingStateLoss()
        }

        val vm = detectedVideosTabViewModel
        val listener = candidateFormatListener

        val adapter = if (vm != null && listener != null) {
            VideoInfoAdapter(
                vm.detectedVideosList.get()?.toList() ?: emptyList(),
                vm,
                listener,
                appUtil,
            )
        } else null

        layoutMngr = WrapContentLinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

        binding = FragmentDetectedVideosTabBinding.inflate(inflater, container, false).apply {
            title.text = getString(
                R.string.found_videos_from,
                vm?.webTabModel?.getTabTextInput()?.get()
            ).split("?").firstOrNull()
            viewModel = vm
            videoInfoList.layoutManager = layoutMngr
            videoInfoList.isNestedScrollingEnabled = true
            videoInfoList.adapter = adapter
            dialogListener = listener
        }

        return binding.root
    }
}
