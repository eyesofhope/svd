package com.myAllVideoBrowser.ui.main.home.browser.homeTab

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.databinding.Observable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.model.Suggestion
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.databinding.FragmentBrowserHomeBinding
import com.myAllVideoBrowser.ui.component.adapter.SuggestionAdapter
import com.myAllVideoBrowser.ui.component.adapter.SuggestionListener
import com.myAllVideoBrowser.ui.component.adapter.TopPageAdapter
import com.myAllVideoBrowser.ui.main.home.MainViewModel
import com.myAllVideoBrowser.ui.main.home.browser.BaseWebTabFragment
import com.myAllVideoBrowser.ui.main.home.browser.BrowserListener
import com.myAllVideoBrowser.ui.main.home.browser.TabManagerProvider
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabFactory
import com.myAllVideoBrowser.ui.main.settings.SettingsFragment
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.AppUtil
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentTransaction
import com.myAllVideoBrowser.R
import kotlinx.coroutines.launch
import javax.inject.Inject

interface BrowserHomeListener : BrowserListener {

    override fun onBrowserReloadClicked() {}
    override fun onTabCloseClicked() {}
    override fun onBrowserStopClicked() {}
    override fun onBrowserBackClicked() {}
    override fun onBrowserForwardClicked() {}
}

class BrowserHomeFragment : BaseWebTabFragment() {

    companion object {
        fun newInstance() = BrowserHomeFragment()
    }

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var appUtil: AppUtil

    lateinit var binding: FragmentBrowserHomeBinding

    private lateinit var openPageIProvider: TabManagerProvider

    private lateinit var homeViewModel: BrowserHomeViewModel

    private lateinit var mainViewModel: MainViewModel

    private lateinit var topPageAdapter: TopPageAdapter

    private lateinit var suggestionAdapter: SuggestionAdapter

    private val tabsListChangeListener = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            updateTabCounter()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        mainViewModel = mainActivity.mainViewModel
        homeViewModel = ViewModelProvider(this, viewModelFactory)[BrowserHomeViewModel::class.java]
        openPageIProvider = mainActivity.mainViewModel.browserServicesProvider!!

        topPageAdapter = TopPageAdapter(requireContext(), emptyList(), itemListener)
        suggestionAdapter = SuggestionAdapter(requireContext(), emptyList(), suggestionListener)

        binding = FragmentBrowserHomeBinding.inflate(inflater, container, false).apply {
            buildWebTabMenu(this.browserHomeMenuButton, true)

            this.viewModel = homeViewModel
            this.mainVModel = mainViewModel
            this.browserMenuListener = menuListener
            this.topPagesGrid.adapter = topPageAdapter

            this.suggestionsList.adapter = suggestionAdapter
            this.suggestionsList.setOnItemClickListener { _, _, position, _ ->
                val item = suggestionAdapter.getItem(position)
                openNewTab(item.content)
            }

            // Top bar shortcuts
            this.btnHelp.setOnClickListener { navigateToHelp() }
            this.btnSettings.setOnClickListener { navigateToSettings() }
            this.tabCounterContainer.setOnClickListener {
                mainViewModel.openNavDrawerEvent.call()
            }

            this.howToDownloadButton.setOnClickListener { navigateToHelp() }

            // Quick URL prefixes
            wireQuickPrefixes(this)

            this.homeEtSearch.setAdapter(suggestionAdapter)
            this.homeEtSearch.addTextChangedListener(onInputHomeSearchChangeListener)
            this.homeEtSearch.imeOptions = EditorInfo.IME_ACTION_GO
            this.homeEtSearch.setOnFocusChangeListener { _, hasFocus ->
                onSearchFocusChanged(hasFocus)
            }
            this.homeEtSearch.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    submitSearch()
                    true
                } else false
            }
            this.goButton.setOnClickListener { submitSearch() }
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        handleFirstStartGuide()

        homeViewModel.start()
        observeSuggestions()
        updateTabCounter()
        openPageIProvider.getTabsListChangeEvent().addOnPropertyChangedCallback(tabsListChangeListener)

        val openingUrl = mainViewModel.openedUrl.get()
        val openingText = mainViewModel.openedText.get()

        if (openingUrl != null) {
            openNewTab(openingUrl)
            mainViewModel.openedUrl.set(null)
        }

        if (openingText != null) {
            openNewTab(openingText)
            mainViewModel.openedText.set(null)
        }
    }

    override fun onDestroyView() {
        openPageIProvider.getTabsListChangeEvent()
            .removeOnPropertyChangedCallback(tabsListChangeListener)
        super.onDestroyView()
    }

    // Bug fix for not updating home page grid after adding new bookmark
    override fun onResume() {
        super.onResume()
        val bookmarksList = mainViewModel.bookmarksList.get()?.toMutableList()
        mainViewModel.bookmarksList.set(bookmarksList)
        updateTabCounter()
    }

    private fun observeSuggestions() {
        homeViewModel.listSuggestions.addOnPropertyChangedCallback(object :
            Observable.OnPropertyChangedCallback() {
            override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                val items = homeViewModel.listSuggestions.get() ?: emptyList()
                suggestionAdapter.setData(items)
                refreshSuggestionsVisibility(items.isNotEmpty())
            }
        })
    }

    private fun onSearchFocusChanged(hasFocus: Boolean) {
        homeViewModel.changeSearchFocus(hasFocus)

        // Smooth animation: scale + alpha on the search bar; show prefix row + suggestions.
        val card = binding.searchBarCard
        card.animate()
            .scaleX(if (hasFocus) 1.02f else 1f)
            .scaleY(if (hasFocus) 1.02f else 1f)
            .setDuration(180)
            .start()

        binding.quickPrefixRow.visibility = if (hasFocus) View.VISIBLE else View.GONE
        refreshSuggestionsVisibility(hasFocus && (homeViewModel.listSuggestions.get()?.isNotEmpty() == true))

        // Hide the favorites scroll while typing so suggestions can take over
        binding.homeScroll.visibility = if (hasFocus) View.GONE else View.VISIBLE
    }

    private fun refreshSuggestionsVisibility(hasItems: Boolean) {
        val isFocused = homeViewModel.isSearchInputFocused.get()
        binding.suggestionsList.visibility =
            if (isFocused && hasItems) View.VISIBLE else View.GONE
    }

    private fun submitSearch() {
        val inputText = (binding.homeEtSearch as EditText).text.toString()
        binding.homeEtSearch.text.clear()
        binding.homeEtSearch.clearFocus()
        appUtil.hideSoftKeyboard(binding.homeEtSearch)
        homeViewModel.viewModelScope.launch { openNewTab(inputText) }
    }

    private fun wireQuickPrefixes(b: FragmentBrowserHomeBinding) {
        val onClick = View.OnClickListener { v ->
            val prefix = (v as TextView).tag?.toString() ?: v.text.toString()
            val current = b.homeEtSearch.text?.toString() ?: ""
            val end = b.homeEtSearch.selectionEnd.coerceAtLeast(0)
            val updated = current.substring(0, end) + prefix + current.substring(end)
            b.homeEtSearch.setText(updated)
            b.homeEtSearch.setSelection((end + prefix.length).coerceAtMost(updated.length))
        }
        for (i in 0 until b.quickPrefixRow.childCount) {
            b.quickPrefixRow.getChildAt(i).setOnClickListener(onClick)
        }
    }

    private fun updateTabCounter() {
        val count = openPageIProvider.getTabsListChangeEvent().get()?.size ?: 1
        binding.tabCounter.text = count.toString()
        binding.tabCounter.contentDescription =
            getString(R.string.tab_counter_content_description, count)
    }

    private val suggestionListener = object : SuggestionListener {
        override fun onItemClicked(suggestion: Suggestion) {
            openNewTab(suggestion.content)
        }
    }

    private fun openNewTab(input: String) {
        if (input.isNotEmpty()) {
            openPageIProvider.getOpenTabEvent().value =
                WebTabFactory.createWebTabFromInput(input, sharedPrefHelper)
        }
    }

    private val onInputHomeSearchChangeListener = object : TextWatcher {
        override fun afterTextChanged(s: Editable) {
            val input = s.toString()
            homeViewModel.searchTextInput.set(input)
            if (!(input.startsWith("http://") || input.startsWith("https://"))) {
                homeViewModel.showSuggestions()
            } else {
                refreshSuggestionsVisibility(false)
            }
            homeViewModel.homePublishSubject.onNext(input)
        }

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }

    private val itemListener = object : TopPageAdapter.TopPagesListener {
        override fun onItemClicked(pageInfo: PageInfo) {
            openNewTab(pageInfo.link)
        }
    }

    private val menuListener = object : BrowserHomeListener {
        override fun onBrowserMenuClicked() {
            showPopupMenu()
        }
    }

    override val popupNavListener = object : PopupNavListener {
        override fun onMenuNewTab() {
            // On the home screen, "New Tab" focuses the URL field for input
            binding.homeEtSearch.requestFocus()
            binding.homeEtSearch.text?.clear()
        }
    }

    private fun handleFirstStartGuide() {
        if (mainActivity.sharedPrefHelper.getIsFirstStart()) {
            mainActivity.settingsViewModel.setIsFirstStart(false)
            navigateToHelp()
        }
    }

    private fun navigateToSettings() {
        try {
            val activityFragmentContainer =
                activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction = requireActivity().supportFragmentManager.beginTransaction()
                transaction.add(it.id, SettingsFragment.newInstance())
                transaction.addToBackStack("settings")
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                transaction.commit()
            }
        } catch (e: ClassCastException) {
            AppLogger.d("Can't get the fragment manager from BrowserHomeFragment")
        }
    }

    override fun shareWebLink() {}

    override fun bookmarkCurrentUrl() {}
}
