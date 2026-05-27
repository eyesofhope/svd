package com.myAllVideoBrowser.ui.main.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.os.LocaleListCompat
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModelProvider
import com.myAllVideoBrowser.BuildConfig
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentSettingsBinding
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.help.HelpFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.IntentUtil
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.SystemUtil
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentTransaction
import javax.inject.Inject

class SettingsFragment : BaseFragment() {

    companion object {
        fun newInstance() = SettingsFragment()
    }

    private data class SearchEngine(val displayName: String, val template: String)

    private val searchEngines: List<SearchEngine> by lazy {
        // Map registry engines to localized display names where translatable strings exist.
        val registryEngines = com.myAllVideoBrowser.util.SearchEngineRegistry.engines.map { e ->
            val display = when (e.name) {
                "DuckDuckGo (Privacy)" -> getString(R.string.search_engine_duckduckgo)
                "DuckDuckGo Lite (Privacy)" -> getString(R.string.search_engine_duckduckgo_lite)
                "Google" -> getString(R.string.search_engine_google)
                "Bing" -> getString(R.string.search_engine_bing)
                "Yahoo" -> getString(R.string.search_engine_yahoo)
                "Ask" -> getString(R.string.search_engine_ask)
                "StartPage" -> getString(R.string.search_engine_startpage)
                "StartPage (Mobile)" -> getString(R.string.search_engine_startpage_mobile)
                "Baidu (Chinese)" -> getString(R.string.search_engine_baidu)
                "Yandex (Russian)" -> getString(R.string.search_engine_yandex)
                else -> e.name
            }
            SearchEngine(display, e.searchUrlTemplate)
        }
        listOf(SearchEngine(getString(R.string.search_engine_custom), "custom")) + registryEngines
    }

    private val languages: List<Pair<String, String>> by lazy {
        listOf(
            getString(R.string.language_default) to "",
            getString(R.string.language_english) to "en",
            getString(R.string.language_german) to "de",
            getString(R.string.language_greek) to "el",
            getString(R.string.language_spanish) to "es",
            getString(R.string.language_french) to "fr",
            getString(R.string.language_hungarian) to "hu",
            getString(R.string.language_italian) to "it",
            getString(R.string.language_japanese) to "ja",
            getString(R.string.language_korean) to "ko",
            getString(R.string.language_dutch) to "nl",
            getString(R.string.language_polish) to "pl",
            getString(R.string.language_portuguese_pt) to "pt-PT",
            getString(R.string.language_portuguese_br) to "pt-BR",
            getString(R.string.language_russian) to "ru",
            getString(R.string.language_serbian) to "sr",
            getString(R.string.language_turkish) to "tr",
            getString(R.string.language_chinese) to "zh-CN",
            getString(R.string.language_indonesian) to "in",
            getString(R.string.language_bengali) to "bn"
        )
    }

    @Inject
    lateinit var fileUtil: FileUtil

    @Inject
    lateinit var intentUtil: IntentUtil

    @Inject
    lateinit var systemUtil: SystemUtil

    @Inject
    lateinit var sharedPrefHelper: SharedPrefHelper

    @Inject
    lateinit var mainActivity: MainActivity

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    private lateinit var dataBinding: FragmentSettingsBinding
    private lateinit var settingsViewModel: SettingsViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        settingsViewModel = mainActivity.settingsViewModel
        dataBinding = FragmentSettingsBinding.inflate(inflater, container, false)
        dataBinding.viewModel = settingsViewModel
        dataBinding.lifecycleOwner = viewLifecycleOwner
        return dataBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupTopRows()
        handleUIEvents()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            parentFragmentManager.popBackStack()
        }

        settingsViewModel.start()
        renderTopRowValues()
    }

    override fun onDestroyView() {
        settingsViewModel.stop()
        super.onDestroyView()
    }

    private fun setupToolbar() {
        dataBinding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun setupTopRows() {
        // Download location row
        dataBinding.rowDownloadLocation.setOnClickListener {
            val location = fileUtil.folderDir.absolutePath
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_download_location)
                .setMessage(location)
                .setPositiveButton(R.string.ok, null)
                .show()
        }

        // Wi-Fi only switch
        dataBinding.switchWifiOnly.isChecked = sharedPrefHelper.getDownloadWifiOnly()
        dataBinding.switchWifiOnly.setOnCheckedChangeListener { _, checked ->
            sharedPrefHelper.setDownloadWifiOnly(checked)
        }

        // Block ads switch
        dataBinding.switchBlockAds.isChecked = sharedPrefHelper.getBlockAds()
        dataBinding.tvBlockAdsState.text =
            getString(if (sharedPrefHelper.getBlockAds()) R.string.settings_state_on else R.string.settings_state_off)
        dataBinding.switchBlockAds.setOnCheckedChangeListener { _, checked ->
            sharedPrefHelper.setBlockAds(checked)
            dataBinding.tvBlockAdsState.text =
                getString(if (checked) R.string.settings_state_on else R.string.settings_state_off)
        }

        // Recently used websites switch
        dataBinding.switchRecentlyUsed.isChecked = sharedPrefHelper.getShowRecentlyUsedWebsites()
        dataBinding.switchRecentlyUsed.setOnCheckedChangeListener { _, checked ->
            sharedPrefHelper.setShowRecentlyUsedWebsites(checked)
        }

        // Search engine row
        dataBinding.rowSearchEngine.setOnClickListener { showSearchEngineDialog() }

        // Clear-cache rows
        dataBinding.rowClearCache.setOnClickListener {
            systemUtil.clearCookies(requireContext())
            Toast.makeText(requireContext(), R.string.settings_cache_cleared, Toast.LENGTH_SHORT).show()
        }
        dataBinding.rowClearHistory.setOnClickListener {
            settingsViewModel.viewModelScopeClearHistory()
            Toast.makeText(requireContext(), R.string.settings_history_cleared, Toast.LENGTH_SHORT).show()
        }
        dataBinding.rowClearCookies.setOnClickListener {
            settingsViewModel.clearCookies()
        }

        // Language row
        dataBinding.rowLanguage.setOnClickListener { showLanguageDialog() }

        // Sync to gallery row
        dataBinding.switchSyncGallery.isChecked = sharedPrefHelper.getSyncToGallery()
        dataBinding.tvSyncState.text =
            getString(if (sharedPrefHelper.getSyncToGallery()) R.string.settings_state_on else R.string.settings_state_off)
        dataBinding.switchSyncGallery.setOnCheckedChangeListener { _, checked ->
            sharedPrefHelper.setSyncToGallery(checked)
            dataBinding.tvSyncState.text =
                getString(if (checked) R.string.settings_state_on else R.string.settings_state_off)
        }

        // Help section rows
        dataBinding.rowHowToDownload.setOnClickListener { navigateToHelp() }
        dataBinding.rowFeedback.setOnClickListener { sendFeedback() }
        dataBinding.rowPrivacy.setOnClickListener { openPrivacyPolicy() }

        dataBinding.tvAppVersion.text = BuildConfig.VERSION_NAME
    }

    private fun renderTopRowValues() {
        dataBinding.tvDownloadLocationValue.text = fileUtil.folderDir.absolutePath
        // Resolve display name from current template so locale changes update the label too.
        val currentTemplate = sharedPrefHelper.getSearchEngineTemplate()
        dataBinding.tvSearchEngineValue.text =
            searchEngines.firstOrNull { it.template == currentTemplate }?.displayName
                ?: sharedPrefHelper.getSearchEngineName()
        val tag = sharedPrefHelper.getAppLanguageTag()
        dataBinding.tvLanguageValue.text = languages.firstOrNull { it.second == tag }?.first
            ?: getString(R.string.settings_language_default)
    }

    private fun showSearchEngineDialog() {
        val current = sharedPrefHelper.getSearchEngineTemplate()
        val defaultIndex = searchEngines.indexOfFirst {
            it.template == SharedPrefHelper.DEFAULT_SEARCH_ENGINE_TEMPLATE
        }.let { if (it == -1) 1 else it }
        val checked = searchEngines.indexOfFirst { it.template == current }
            .let { if (it == -1) defaultIndex else it }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_search_engine)
            .setSingleChoiceItems(
                searchEngines.map { it.displayName }.toTypedArray(),
                checked
            ) { dialog, which ->
                val picked = searchEngines[which]
                if (picked.template == "custom") {
                    dialog.dismiss()
                    showCustomSearchEngineDialog()
                } else {
                    sharedPrefHelper.setSearchEngineName(picked.displayName)
                    sharedPrefHelper.setSearchEngineTemplate(picked.template)
                    dataBinding.tvSearchEngineValue.text = picked.displayName
                    dialog.dismiss()
                }
            }
            .setNegativeButton(R.string.dialog_download_cancel, null)
            .show()
    }

    private fun showCustomSearchEngineDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.search_engine_custom_hint)
            setText(sharedPrefHelper.getSearchEngineTemplate())
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.search_engine_custom)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.contains("%s")) {
                    sharedPrefHelper.setSearchEngineName(getString(R.string.search_engine_custom))
                    sharedPrefHelper.setSearchEngineTemplate(text)
                    dataBinding.tvSearchEngineValue.text = getString(R.string.search_engine_custom)
                }
            }
            .setNegativeButton(R.string.dialog_download_cancel, null)
            .show()
    }

    private fun showLanguageDialog() {
        val current = sharedPrefHelper.getAppLanguageTag()
        val checked = languages.indexOfFirst { it.second == current }
            .let { if (it == -1) 0 else it }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_language_options)
            .setSingleChoiceItems(
                languages.map { it.first }.toTypedArray(),
                checked
            ) { dialog, which ->
                val pair = languages[which]
                sharedPrefHelper.setAppLanguageTag(pair.second)
                dataBinding.tvLanguageValue.text = pair.first
                AppCompatDelegate.setApplicationLocales(
                    if (pair.second.isBlank()) LocaleListCompat.getEmptyLocaleList()
                    else LocaleListCompat.forLanguageTags(pair.second)
                )
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_download_cancel, null)
            .show()
    }

    private fun sendFeedback() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_SUBJECT, "${getString(R.string.app_name)} feedback")
        }
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(requireContext(), R.string.video_share_message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPrivacyPolicy() {
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/")
                )
            )
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun navigateToHelp() {
        try {
            val container =
                requireActivity().findViewById<FragmentContainerView>(R.id.fragment_container_view)
            val tx = requireActivity().supportFragmentManager.beginTransaction()
            tx.add(container.id, HelpFragment.newInstance())
            tx.addToBackStack("help_from_settings")
            tx.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            tx.commit()
        } catch (_: Throwable) {}
    }

    private fun handleUIEvents() {
        settingsViewModel.clearCookiesEvent.observe(viewLifecycleOwner) {
            systemUtil.clearCookies(context)
            Toast.makeText(requireContext(), R.string.cookies_cleared, Toast.LENGTH_SHORT).show()
        }
        settingsViewModel.openVideoFolderEvent.observe(viewLifecycleOwner) {
            intentUtil.openVideoFolder(context, fileUtil.folderDir.path)
        }
    }
}
