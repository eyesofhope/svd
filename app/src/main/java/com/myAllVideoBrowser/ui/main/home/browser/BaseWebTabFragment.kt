package com.myAllVideoBrowser.ui.main.home.browser

import android.view.Gravity
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ShareCompat
import androidx.databinding.Observable
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.lifecycleScope
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.bookmarks.BookmarksFragment
import com.myAllVideoBrowser.ui.main.help.HelpFragment
import com.myAllVideoBrowser.ui.main.history.HistoryFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabFactory
import com.myAllVideoBrowser.ui.main.settings.SettingsFragment
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.SharedPrefHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject


abstract class BaseWebTabFragment : BaseFragment() {
    @Inject
    lateinit var mainActivity: MainActivity

    @Inject
    lateinit var sharedPrefHelper: SharedPrefHelper

    private var popupMenu: PopupMenu? = null

    private val darkModeCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (!isAdded) {
                return
            }
            lifecycleScope.launch(Dispatchers.Main) {
                popupMenu?.menu?.findItem(R.id.is_dark)?.isChecked =
                    mainActivity.settingsViewModel.isDarkMode.get()
            }
        }
    }

    private val autoDarkModeCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (!isAdded) {
                return
            }
            lifecycleScope.launch(Dispatchers.Main) {
                popupMenu?.menu?.findItem(R.id.is_dark)?.isEnabled =
                    !mainActivity.settingsViewModel.isAutoDarkMode.get()
            }
        }
    }

    private val desktopModeCallback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (!isAdded) {
                return
            }
            lifecycleScope.launch(Dispatchers.Main) {
                popupMenu?.menu?.findItem(R.id.desktop_mode)?.isChecked =
                    mainActivity.settingsViewModel.isDesktopMode.get()
            }
        }
    }

    override fun onDestroyView() {
        popupMenu?.dismiss()
        popupMenu = null

        mainActivity.settingsViewModel.isDarkMode.removeOnPropertyChangedCallback(darkModeCallback)
        mainActivity.settingsViewModel.isAutoDarkMode.removeOnPropertyChangedCallback(autoDarkModeCallback)
        mainActivity.settingsViewModel.isDesktopMode.removeOnPropertyChangedCallback(desktopModeCallback)

        super.onDestroyView()
    }

    abstract fun shareWebLink()

    abstract fun bookmarkCurrentUrl()

    fun buildWebTabMenu(browserMenu: View, isHomeTab: Boolean) {
        if (popupMenu == null) {
            popupMenu = buildPopupMenu(browserMenu)

            val menu = popupMenu!!.menu

            // Sync initial checkable states
            menu.findItem(R.id.is_dark)?.let {
                it.isChecked = mainActivity.settingsViewModel.isDarkMode.get()
                it.isEnabled = !mainActivity.settingsViewModel.isAutoDarkMode.get()
            }
            menu.findItem(R.id.desktop_mode)?.isChecked =
                mainActivity.settingsViewModel.isDesktopMode.get() == true

            popupMenu!!.setForceShowIcon(true)

            mainActivity.settingsViewModel.isDarkMode.addOnPropertyChangedCallback(darkModeCallback)
            mainActivity.settingsViewModel.isAutoDarkMode.addOnPropertyChangedCallback(autoDarkModeCallback)
            mainActivity.settingsViewModel.isDesktopMode.addOnPropertyChangedCallback(desktopModeCallback)

            // Hide tab-only items when on the home tab popup
            menu.findItem(R.id.share_link)?.isVisible = !isHomeTab
            menu.findItem(R.id.bookmark)?.isVisible = !isHomeTab
        }
    }

    fun showPopupMenu() {
        popupMenu?.show()
    }

    open fun setIsDesktop(isDesktop: Boolean) {
        mainActivity.settingsViewModel.setIsDesktopMode(isDesktop)

        val text = if (isDesktop) {
            requireContext().getString(R.string.desktop_mode_on)
        } else {
            requireContext().getString(R.string.desktop_mode_off)
        }

        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    private fun openNewTabFromMenu(isIncognito: Boolean) {
        val provider = mainActivity.mainViewModel.browserServicesProvider ?: return
        val newTab = if (isIncognito) {
            WebTabFactory.createIncognitoTabFromInput("", sharedPrefHelper)
        } else {
            WebTabFactory.createWebTabFromInput("", sharedPrefHelper)
        }

        // Defer the dispatch by one frame. The PopupMenu is in the middle of
        // dismissing when this fires; on a fresh app launch the openPageEvent
        // observer in BrowserFragment is occasionally not active yet because
        // the popup's transient window stole focus. Posting to the next loop
        // tick guarantees the popup tear-down has completed and the
        // BrowserFragment's view lifecycle is back at STARTED, so the
        // SingleLiveEvent observer is guaranteed to fire.
        view?.post {
            // For non-incognito empty input, factory returns HOME_TAB. In that
            // case, jump to the home tab instead of inserting a duplicate.
            if (!isIncognito && newTab == com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab.HOME_TAB) {
                provider.getCurrentTabIndex().set(HOME_TAB_INDEX)
            } else {
                provider.getOpenTabEvent().value = newTab
            }
        }
    }

    private fun buildPopupMenu(view: View): PopupMenu {
        val popupMenu = PopupMenu(requireContext(), view)

        popupMenu.gravity = Gravity.END
        popupMenu.menuInflater.inflate(R.menu.menu_browser, popupMenu.menu)

        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.new_tab_menu -> {
                    openNewTabFromMenu(isIncognito = false)
                    true
                }

                R.id.incognito_tab -> {
                    openNewTabFromMenu(isIncognito = true)
                    true
                }

                R.id.share_link -> {
                    shareWebLink()
                    true
                }

                R.id.history_screen_menu_item -> {
                    navigateToHistory()
                    true
                }

                R.id.bookmarks_list -> {
                    navigateToBookMarks()
                    true
                }

                R.id.bookmark -> {
                    bookmarkCurrentUrl()
                    true
                }

                R.id.desktop_mode -> {
                    menuItem.isChecked = !menuItem.isChecked
                    setIsDesktop(menuItem.isChecked)
                    false
                }

                R.id.settings -> {
                    navigateToSettings()
                    true
                }

                R.id.is_dark -> {
                    mainActivity.settingsViewModel.setIsDarkMode(!mainActivity.settingsViewModel.isDarkMode.get())
                    true
                }

                else -> false
            }
        }

        return popupMenu
    }

    private fun navigateToHistory() {
        try {
            val currentFragment = this
            val activityFragmentContainer =
                currentFragment.activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction =
                    currentFragment.requireActivity().supportFragmentManager.beginTransaction()
                transaction.add(it.id, HistoryFragment.newInstance())
                transaction.addToBackStack("history")
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                transaction.commit()
            }
        } catch (e: ClassCastException) {
            AppLogger.d("Can't get the fragment manager with this")
        }
    }

    fun shareLink(url: String?) {
        ShareCompat.IntentBuilder(mainActivity).setType("text/plain").setChooserTitle("Share Link")
            .setText(url).startChooser()
    }

    private fun navigateToSettings() {
        try {
            val currentFragment = this
            val activityFragmentContainer =
                currentFragment.activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction =
                    currentFragment.requireActivity().supportFragmentManager.beginTransaction()
                transaction.add(it.id, SettingsFragment.newInstance())
                transaction.addToBackStack("settings")
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                transaction.commit()
            }
        } catch (e: ClassCastException) {
            AppLogger.d("Can't get the fragment manager with this")
        }
    }

    fun navigateToHelp() {
        try {
            val currentFragment = this
            val activityFragmentContainer =
                currentFragment.activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction =
                    currentFragment.requireActivity().supportFragmentManager.beginTransaction()
                transaction.add(it.id, HelpFragment.newInstance())
                transaction.addToBackStack("help")
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                transaction.commit()
            }
        } catch (e: ClassCastException) {
            AppLogger.d("Can't get the fragment manager with this")
        }
    }

    private fun navigateToBookMarks() {
        try {
            val currentFragment = this
            val activityFragmentContainer =
                currentFragment.activity?.findViewById<FragmentContainerView>(R.id.fragment_container_view)
            activityFragmentContainer?.let {
                val transaction =
                    currentFragment.requireActivity().supportFragmentManager.beginTransaction()
                transaction.add(it.id, BookmarksFragment.newInstance())
                transaction.addToBackStack("bookmarks")
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                transaction.commit()
            }
        } catch (e: ClassCastException) {
            AppLogger.d("Can't get the fragment manager with this")
        }
    }
}
