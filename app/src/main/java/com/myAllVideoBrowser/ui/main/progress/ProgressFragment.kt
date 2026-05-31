package com.myAllVideoBrowser.ui.main.progress

import android.content.Context
import android.os.Bundle
import android.os.StatFs
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Recycler
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentProgressBinding
import com.myAllVideoBrowser.ui.component.adapter.ProgressAdapter
import com.myAllVideoBrowser.ui.component.adapter.ProgressListener
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.home.MainViewModel
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import javax.inject.Inject

/**
 * Redesigned download / progress page.
 *
 * Beyond the old card-list it now provides:
 *  - A "Downloading N" section header with a counter chip.
 *  - A storage chip pinned to the bottom showing app storage usage.
 *  - A 3-dot top overflow menu: Download all / Pause all / Delete all / Batch select.
 *  - Per-row interactions: animated download icon, play preview, cross-with-confirmation.
 *  - Long-press to enter multi-select [ActionMode] with batch delete.
 */
class ProgressFragment : BaseFragment() {

    companion object {
        fun newInstance() = ProgressFragment()
    }

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var mainActivity: MainActivity

    private lateinit var progressViewModel: ProgressViewModel
    private lateinit var mainViewModel: MainViewModel
    private lateinit var dataBinding: FragmentProgressBinding
    private lateinit var progressAdapter: ProgressAdapter

    private var actionMode: ActionMode? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        mainViewModel = mainActivity.mainViewModel
        progressViewModel = mainActivity.progressViewModel
        progressAdapter = ProgressAdapter(emptyList(), progressListener)

        dataBinding = FragmentProgressBinding.inflate(inflater, container, false).apply {
            val managerL =
                WrapContentLinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            this.mainViewModel = mainActivity.mainViewModel
            this.viewModel = progressViewModel
            this.rvProgress.layoutManager = managerL
            this.rvProgress.adapter = progressAdapter
        }

        setupToolbarMenu()
        observeProgressForChrome()
        renderStorageChip()

        return dataBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        handleDownloadVideoEvent()
    }

    override fun onPause() {
        // This fragment is hosted in a ViewPager2; switching tabs moves it from
        // RESUMED to STARTED without destroying it. Finish the activity-level
        // ActionMode here so the "N selected" banner and its action icons don't
        // linger over the other tabs.
        actionMode?.finish()
        super.onPause()
    }

    override fun onDestroyView() {
        progressViewModel.progressInfos.removeOnPropertyChangedCallback(progressInfosCallback)
        if (::dataBinding.isInitialized) {
            dataBinding.root.removeCallbacks(updateChromeRunnable)
        }
        actionMode?.finish()
        actionMode = null
        super.onDestroyView()
    }

    // region toolbar menu --------------------------------------------------------------

    private fun setupToolbarMenu() {
        // The MaterialToolbar's overflow icon comes from the theme by default.
        // Force a known vector here so the 3-dot affordance always reads the
        // same on screens with custom themes.
        dataBinding.toolbar.overflowIcon = androidx.appcompat.content.res.AppCompatResources
            .getDrawable(requireContext(), R.drawable.more_vert_24px)
        dataBinding.toolbar.setOnMenuItemClickListener { item ->
            onTopActionClicked(item.itemId)
        }
    }

    private fun onTopActionClicked(itemId: Int): Boolean {
        return when (itemId) {
            R.id.action_resume_all -> {
                progressViewModel.resumeAllDownloads(); true
            }
            R.id.action_pause_all -> {
                progressViewModel.pauseAllDownloads(); true
            }
            R.id.action_delete_all -> {
                confirmDeleteAll(); true
            }
            R.id.action_batch_select -> {
                val firstId =
                    progressViewModel.progressInfos.get()?.firstOrNull()?.downloadId
                if (firstId != null) {
                    progressAdapter.enterSelectionMode(firstId)
                    startActionMode()
                }
                true
            }
            else -> false
        }
    }

    // endregion ------------------------------------------------------------------------

    // region chrome observers ----------------------------------------------------------

    private val progressInfosCallback = object : androidx.databinding.Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(
            sender: androidx.databinding.Observable?,
            propertyId: Int,
        ) {
            val binding = if (::dataBinding.isInitialized) dataBinding else return
            binding.root.post(updateChromeRunnable)
        }
    }

    private fun observeProgressForChrome() {
        // The ObservableField is mutated from a background coroutine
        // (`ProgressViewModel.downloadProgressStartListen`), so the callback
        // also fires on that thread. We must hop to the main thread before
        // touching any view, and bail out if the fragment's view is gone.
        progressViewModel.progressInfos.addOnPropertyChangedCallback(progressInfosCallback)
        // Apply initial state directly — we are still on the main thread here.
        renderCounts(progressViewModel.progressInfos.get().orEmpty())
    }

    /** Re-runs every chrome refresh on the main thread. Stored as a field so
     *  successive emissions can be coalesced via [View.post]. */
    private val updateChromeRunnable = Runnable {
        if (view == null || !::dataBinding.isInitialized) return@Runnable
        val list = progressViewModel.progressInfos.get().orEmpty()
        renderCounts(list)
        if (list.isEmpty() && actionMode != null) {
            actionMode?.finish()
        }
    }

    /**
     * Refreshes the section-header counters: the neutral total chip and a
     * live badge on the bottom-nav "Downloading" tab that surfaces how many
     * downloads are currently moving. The badge is hidden when nothing is
     * actively downloading so it never feels like noise.
     */
    private fun renderCounts(list: List<com.myAllVideoBrowser.data.local.room.entity.ProgressInfo>) {
        dataBinding.chipCount.text = list.size.toString()

        val activeCount = list.count { info ->
            when (info.downloadStatus) {
                VideoTaskState.DOWNLOADING,
                VideoTaskState.PREPARE,
                VideoTaskState.START,
                VideoTaskState.PROXYREADY,
                VideoTaskState.PENDING -> true
                else -> false
            }
        }

        // Push the active-count to the bottom-nav badge so the user can see
        // the live count from any screen, not just this fragment.
        mainActivity.setDownloadingBadgeCount(activeCount)
    }

    private fun renderStorageChip() {
        val (used, total) = computeUsageBytes()
        if (total > 0) {
            dataBinding.tvStorage.text = getString(
                R.string.progress_storage_format,
                FileUtil.getFileSizeReadable(used.toDouble()),
                FileUtil.getFileSizeReadable(total.toDouble()),
            )
        } else {
            dataBinding.storageChip.visibility = View.GONE
        }
    }

    private fun computeUsageBytes(): Pair<Long, Long> {
        // Use the primary data directory — same volume the downloads land on
        // for the typical user. We avoid touching FileUtil here to keep the
        // chip resilient if the download dir hasn't been initialised yet.
        return try {
            val path = android.os.Environment.getDataDirectory().absolutePath
            val stat = StatFs(path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            (total - free) to total
        } catch (t: Throwable) {
            AppLogger.e("Storage chip stat failed: ${t.message}")
            0L to 0L
        }
    }

    // endregion ------------------------------------------------------------------------

    private fun handleDownloadVideoEvent() {
        mainViewModel.downloadVideoEvent.observe(viewLifecycleOwner) { videoInfo ->
            val currentOriginal = videoInfo.originalUrl
            mainViewModel.currentOriginal.set(currentOriginal)
            progressViewModel.downloadVideo(videoInfo)
        }
    }

    // region row callbacks -------------------------------------------------------------

    private val progressListener = object : ProgressListener {
        override fun onRemoveClicked(downloadId: Long) {
            confirmDeleteOne(downloadId)
        }

        override fun onPauseClicked(downloadId: Long) {
            progressViewModel.pauseDownload(downloadId)
        }

        override fun onResumeClicked(downloadId: Long) {
            progressViewModel.resumeDownload(downloadId)
        }

        override fun onPreviewClicked(downloadId: Long) {
            // Wired but no preview screen exists yet — the play icon now stands in
            // for the previous globe icon, ready for a future "open partial file"
            // feature. We log so the action is observable in QA builds.
            AppLogger.d("Preview tapped for download $downloadId")
        }

        override fun onItemLongPressed(downloadId: Long) {
            if (actionMode == null) {
                progressAdapter.enterSelectionMode(downloadId)
                startActionMode()
            } else {
                onItemTapped(downloadId)
            }
        }

        override fun onItemTapped(downloadId: Long) {
            val count = progressAdapter.toggleSelection(downloadId)
            if (count == 0) {
                actionMode?.finish()
            } else {
                actionMode?.title =
                    getString(R.string.progress_action_mode_title, count)
                actionMode?.invalidate()
            }
        }
    }

    // endregion ------------------------------------------------------------------------

    // region confirmation dialogs ------------------------------------------------------

    private fun confirmDeleteOne(downloadId: Long) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.progress_delete_dialog_title)
            .setMessage(R.string.progress_delete_dialog_message)
            .setNegativeButton(R.string.progress_delete_cancel, null)
            .setPositiveButton(R.string.progress_delete_confirm) { _, _ ->
                progressViewModel.cancelDownload(downloadId, true)
            }
            .show()
    }

    private fun confirmDeleteAll() {
        val count = progressViewModel.progressInfos.get().orEmpty().size
        if (count == 0) return
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.progress_delete_many_title, count))
            .setMessage(R.string.progress_delete_many_message)
            .setNegativeButton(R.string.progress_delete_cancel, null)
            .setPositiveButton(R.string.progress_delete_confirm) { _, _ ->
                progressViewModel.cancelAllDownloads(true)
            }
            .show()
    }

    private fun confirmDeleteSelection(ids: List<Long>) {
        if (ids.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.progress_delete_many_title, ids.size))
            .setMessage(R.string.progress_delete_many_message)
            .setNegativeButton(R.string.progress_delete_cancel, null)
            .setPositiveButton(R.string.progress_delete_confirm) { _, _ ->
                progressViewModel.cancelDownloads(ids, true)
                actionMode?.finish()
            }
            .show()
    }

    // endregion ------------------------------------------------------------------------

    // region action mode ---------------------------------------------------------------

    private fun startActionMode() {
        if (actionMode != null) return
        val activity = requireActivity() as? AppCompatActivity ?: return
        actionMode = activity.startSupportActionMode(actionModeCallback)
        actionMode?.title = getString(
            R.string.progress_action_mode_title,
            progressAdapter.selectedCount,
        )
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_progress_action_mode, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.am_select_all -> {
                    progressAdapter.selectAll()
                    mode.title = getString(
                        R.string.progress_action_mode_title,
                        progressAdapter.selectedCount,
                    )
                    true
                }
                R.id.am_delete -> {
                    confirmDeleteSelection(progressAdapter.selectedDownloadIds())
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            progressAdapter.exitSelectionMode()
            actionMode = null
        }
    }

    // endregion ------------------------------------------------------------------------
}

class WrapContentLinearLayoutManager : LinearLayoutManager {
    constructor(context: Context?) : super(context)
    constructor(context: Context?, orientation: Int, reverseLayout: Boolean) : super(
        context, orientation, reverseLayout
    )

    constructor(
        context: Context?, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    override fun onLayoutChildren(recycler: Recycler, state: RecyclerView.State) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (e: IndexOutOfBoundsException) {
            AppLogger.e("meet a IOOBE in RecyclerView")
        }
    }
}
