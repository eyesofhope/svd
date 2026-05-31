package com.myAllVideoBrowser.ui.main.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
import java.io.File
import javax.inject.Inject

class SettingsFragment : BaseFragment() {

    companion object {
        fun newInstance() = SettingsFragment()
    }

    private data class SearchEngine(val displayName: String, val template: String)

    private val searchEngines: List<SearchEngine> by lazy {
        // Map registry engines (DuckDuckGo, Google, Bing, Yahoo) to localized display names.
        com.myAllVideoBrowser.util.SearchEngineRegistry.engines.map { e ->
            val display = when (e.name) {
                "DuckDuckGo (Privacy)" -> getString(R.string.search_engine_duckduckgo)
                "Google" -> getString(R.string.search_engine_google)
                "Bing" -> getString(R.string.search_engine_bing)
                "Yahoo" -> getString(R.string.search_engine_yahoo)
                else -> e.name
            }
            SearchEngine(display, e.searchUrlTemplate)
        }
    }

    /**
     * Languages exposed in the picker. We list only locales that ship with translations
     * in res/values-* and that are also declared in res/xml/locales_config.xml so the
     * AppCompat backport actually applies them. English is the default selection.
     */
    private val languages: List<Pair<String, String>> by lazy {
        listOf(
            getString(R.string.language_english) to "en",
            getString(R.string.language_french) to "fr",
            getString(R.string.language_korean) to "ko",
            getString(R.string.language_polish) to "pl",
            getString(R.string.language_portuguese_br) to "pt-BR",
            getString(R.string.language_russian) to "ru",
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
    private lateinit var folderPickerLauncher: ActivityResultLauncher<Uri?>

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

        folderPickerLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
                if (treeUri != null) {
                    handleFolderPicked(treeUri)
                }
            }

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
        // Download location row → opens the Files picker so the user can choose any folder.
        dataBinding.rowDownloadLocation.setOnClickListener {
            launchFolderPicker()
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
            clearWebViewCache()
            Toast.makeText(requireContext(), R.string.settings_cache_cleared, Toast.LENGTH_SHORT)
                .show()
        }
        dataBinding.rowClearHistory.setOnClickListener {
            settingsViewModel.viewModelScopeClearHistory()
            Toast.makeText(requireContext(), R.string.settings_history_cleared, Toast.LENGTH_SHORT)
                .show()
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
            // When the user turns sync on, scan the existing downloads folder so previous files
            // surface in the gallery without waiting for the next download.
            if (checked) {
                fileUtil.scanFileIfEnabled(requireContext().applicationContext, fileUtil.folderDir)
            }
        }

        // Help section rows
        dataBinding.rowHowToDownload.setOnClickListener { navigateToHelp() }
        dataBinding.rowFeedback.setOnClickListener { sendFeedback() }
        dataBinding.rowPrivacy.setOnClickListener { openPrivacyPolicy() }

        dataBinding.tvAppVersion.text = BuildConfig.VERSION_NAME
    }

    private fun renderTopRowValues() {
        dataBinding.tvDownloadLocationValue.text =
            sharedPrefHelper.getCustomDownloadFolderPath()?.takeIf { it.isNotBlank() }
                ?: fileUtil.folderDir.absolutePath
        // Resolve display name from current template so locale changes update the label too.
        val currentTemplate = sharedPrefHelper.getSearchEngineTemplate()
        dataBinding.tvSearchEngineValue.text =
            searchEngines.firstOrNull { it.template == currentTemplate }?.displayName
                ?: sharedPrefHelper.getSearchEngineName()
        val tag = currentLanguageTagOrEnglish()
        dataBinding.tvLanguageValue.text = languages.firstOrNull { it.second == tag }?.first
            ?: getString(R.string.language_english)
    }

    private fun currentLanguageTagOrEnglish(): String {
        val saved = sharedPrefHelper.getAppLanguageTag()
        return if (saved.isBlank()) "en" else saved
    }

    private fun showSearchEngineDialog() {
        val current = sharedPrefHelper.getSearchEngineTemplate()
        val defaultIndex = searchEngines.indexOfFirst {
            it.template == SharedPrefHelper.DEFAULT_SEARCH_ENGINE_TEMPLATE
        }.let { if (it == -1) 0 else it }
        val checked = searchEngines.indexOfFirst { it.template == current }
            .let { if (it == -1) defaultIndex else it }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_search_engine)
            .setSingleChoiceItems(
                searchEngines.map { it.displayName }.toTypedArray(),
                checked
            ) { dialog, which ->
                val picked = searchEngines[which]
                sharedPrefHelper.setSearchEngineName(picked.displayName)
                sharedPrefHelper.setSearchEngineTemplate(picked.template)
                dataBinding.tvSearchEngineValue.text = picked.displayName
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_download_cancel, null)
            .show()
    }

    private fun showLanguageDialog() {
        val current = currentLanguageTagOrEnglish()
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
                // AppCompat (API 33+: system; older: AppCompat backport) recreates active
                // activities for us when the locale changes, so no manual recreate() needed.
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(pair.second)
                )
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_download_cancel, null)
            .show()
    }

    private fun launchFolderPicker() {
        try {
            folderPickerLauncher.launch(null)
        } catch (e: Throwable) {
            Toast.makeText(
                requireContext(),
                R.string.settings_message_open_folder,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Persist the picked tree URI and translate it into a real filesystem path that the
     * existing download workers can write to. SAF "external" tree URIs map to
     * /storage/<volume>/<path> for primary storage; we save that path so [FileUtil.folderDir]
     * can use it. When translation fails (cloud providers, SD-card volumes the app cannot
     * write to as a regular File), we keep using the previous folder and tell the user.
     */
    private fun handleFolderPicked(treeUri: Uri) {
        val ctx = requireContext().applicationContext
        try {
            ctx.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Throwable) {
            // Some pickers don't grant persistable permission; keep going.
        }

        val resolved = resolveTreeUriToFile(treeUri)
        if (resolved == null) {
            Toast.makeText(
                requireContext(),
                R.string.settings_message_open_folder,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (!resolved.exists()) resolved.mkdirs()
        if (!resolved.canWrite()) {
            Toast.makeText(
                requireContext(),
                R.string.settings_message_open_folder,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        sharedPrefHelper.setCustomDownloadFolderUri(treeUri.toString())
        sharedPrefHelper.setCustomDownloadFolderPath(resolved.absolutePath)
        FileUtil.OVERRIDE_DOWNLOAD_TREE_URI = treeUri.toString()
        FileUtil.OVERRIDE_DOWNLOAD_PATH = resolved.absolutePath

        dataBinding.tvDownloadLocationValue.text = resolved.absolutePath
    }

    private fun resolveTreeUriToFile(treeUri: Uri): File? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parts = docId.split(":", limit = 2)
            if (parts.size != 2) return null
            val type = parts[0]
            val relative = parts[1]
            val base: File = when (type) {
                "primary" -> Environment.getExternalStorageDirectory()
                else -> {
                    // Non-primary volumes (SD card, USB) cannot be written to via java.io.File on
                    // modern Android, so we don't pretend they can be the new download root.
                    return null
                }
            }
            if (relative.isBlank()) base else File(base, relative)
        } catch (_: Throwable) {
            null
        }
    }

    private fun clearWebViewCache() {
        try {
            // Clear the disk + memory cache shared by all WebView instances in the process.
            WebView(requireContext().applicationContext).apply {
                clearCache(true)
                clearFormData()
                clearHistory()
                destroy()
            }
            WebStorage.getInstance().deleteAllData()
        } catch (e: Throwable) {
            // Even if the WebView constructor throws (devices without WebView), we still want
            // the user-facing toast in setupTopRows to confirm the action was attempted.
        }
    }

    private fun sendFeedback() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_SUBJECT, "${getString(R.string.app_name_full)} feedback")
        }
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(requireContext(), R.string.video_share_message, Toast.LENGTH_SHORT)
                .show()
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
            // SystemUtil already shows its own toast; avoid a duplicate here.
        }
        settingsViewModel.openVideoFolderEvent.observe(viewLifecycleOwner) {
            intentUtil.openVideoFolder(context, fileUtil.folderDir.path)
        }
    }
}
