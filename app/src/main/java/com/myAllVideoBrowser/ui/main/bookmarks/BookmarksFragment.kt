package com.myAllVideoBrowser.ui.main.bookmarks


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.databinding.Observable
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.databinding.FragmentBookmarksBinding
import com.myAllVideoBrowser.ui.component.adapter.BookmarksAdapter
import com.myAllVideoBrowser.ui.component.adapter.BookmarksListener
import com.myAllVideoBrowser.ui.component.adapter.ReorderableItemTouchHelperCallback
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabFactory
import com.myAllVideoBrowser.ui.main.progress.WrapContentLinearLayoutManager
import com.myAllVideoBrowser.util.SharedPrefHelper
import javax.inject.Inject

class BookmarksFragment : BaseFragment() {

    private lateinit var dataBinding: FragmentBookmarksBinding

    @Inject
    lateinit var mainActivity: MainActivity

    @Inject
    lateinit var sharedPrefHelper: SharedPrefHelper

    private lateinit var bookmarksAdapter: BookmarksAdapter

    private var bookmarksCached = mutableListOf<PageInfo>()

    private var hasChanged = false

    private val bookmarksObserver = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            updateEmptyState()
        }
    }

    companion object {
        @JvmStatic
        fun newInstance() = BookmarksFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val mainModel = mainActivity.mainViewModel

        bookmarksAdapter = BookmarksAdapter(mutableListOf(), listener)
        val touchHelperCallback = ReorderableItemTouchHelperCallback(bookmarksAdapter)
        val itemTouchHelper = ItemTouchHelper(touchHelperCallback)

        val layoutManager =
            WrapContentLinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

        dataBinding = FragmentBookmarksBinding.inflate(inflater, container, false).apply {
            this.mainVModel = mainModel
            this.bookmarksList.layoutManager = layoutManager
            this.bookmarksList.adapter = bookmarksAdapter
            itemTouchHelper.attachToRecyclerView(this.bookmarksList)

            bookmarksToolbar.setNavigationOnClickListener {
                parentFragmentManager.popBackStack()
            }
            btnAddBookmark.setOnClickListener { showAddBookmarkDialog() }
        }

        mainModel.bookmarksList.addOnPropertyChangedCallback(bookmarksObserver)
        updateEmptyState()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            parentFragmentManager.popBackStack()
        }

        return dataBinding.root
    }

    val listener = object : BookmarksListener {
        override fun onBookmarkOpenClicked(view: View, bookmarkItem: PageInfo) {
            mainActivity.mainViewModel.browserServicesProvider?.getOpenTabEvent()?.value =
                WebTabFactory.createWebTabFromInput(bookmarkItem.link, sharedPrefHelper)
            parentFragmentManager.popBackStack()
        }

        override fun onBookmarkMove(bookmarks: MutableList<PageInfo>) {
            bookmarksCached = bookmarks.toMutableList()
            hasChanged = true
        }

        override fun onBookmarkDelete(bookmarks: MutableList<PageInfo>, position: Int) {
            bookmarks.removeAt(position)
            bookmarksCached = bookmarks
            hasChanged = true
        }

        override fun onBookmarkDeleteClicked(bookmarkItem: PageInfo) {
            confirmDelete(bookmarkItem)
        }
    }

    private fun confirmDelete(pageInfo: PageInfo) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.remove_favorite_title))
            .setMessage(
                getString(
                    R.string.remove_favorite_message,
                    pageInfo.name.ifBlank { pageInfo.link }
                )
            )
            .setPositiveButton(R.string.remove) { dialog, _ ->
                mainActivity.mainViewModel.removeBookmark(pageInfo)
                Toast.makeText(ctx, R.string.removed_from_bookmarks, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_download_cancel, null)
            .show()
    }

    private fun showAddBookmarkDialog() {
        val ctx = context ?: return
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val nameInput = EditText(ctx).apply {
            hint = getString(R.string.bookmark_dialog_name_hint)
        }
        val urlInput = EditText(ctx).apply {
            hint = getString(R.string.bookmark_dialog_url_hint)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(nameInput)
        container.addView(urlInput)

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.add_bookmark)
            .setView(container)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                val rawUrl = urlInput.text.toString().trim()
                val name = nameInput.text.toString().trim().ifBlank { rawUrl }
                if (rawUrl.isNotEmpty()) {
                    val url = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                        rawUrl
                    } else {
                        "https://$rawUrl"
                    }
                    mainActivity.mainViewModel.addBookmark(url, name)
                    Toast.makeText(ctx, R.string.added_to_bookmarks, Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_download_cancel, null)
            .show()
    }

    private fun updateEmptyState() {
        if (!::dataBinding.isInitialized) return
        val isEmpty = mainActivity.mainViewModel.bookmarksList.get().isNullOrEmpty()
        dataBinding.bookmarksEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        if (hasChanged) {
            mainActivity.mainViewModel.updateBookmarks(bookmarksCached)
        }

        mainActivity.mainViewModel.bookmarksList.removeOnPropertyChangedCallback(bookmarksObserver)
        super.onDestroy()
    }
}
