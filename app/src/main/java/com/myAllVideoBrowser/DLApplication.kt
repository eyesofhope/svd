package com.myAllVideoBrowser

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.work.Configuration
import androidx.work.WorkManager
import com.myAllVideoBrowser.di.component.DaggerAppComponent
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.downloaders.generic_downloader.DaggerWorkerFactory
import com.myAllVideoBrowser.util.proxy_utils.ProxyService
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import dagger.android.AndroidInjector
import dagger.android.DaggerApplication
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

open class DLApplication : DaggerApplication() {
    companion object {
        const val DEBUG_TAG: String = "YOUTUBE_DL_DEBUG_TAG"
        var isProxyServiceStarted = false
    }

    private lateinit var androidInjector: AndroidInjector<out DaggerApplication>

    @Inject
    lateinit var workerFactory: DaggerWorkerFactory

    @Inject
    lateinit var sharedPrefHelper: SharedPrefHelper

    @Inject
    lateinit var fileUtil: FileUtil

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        androidInjector = DaggerAppComponent.builder().application(this).build()
    }

    public override fun applicationInjector(): AndroidInjector<out DaggerApplication> =
        androidInjector

    override fun onCreate() {
        super.onCreate()

        ContextUtils.initApplicationContext(applicationContext)

        applySavedAppLocaleIfNeeded()

        initializeFileUtils()

        // Proxy feature has been removed from the user-facing app. Force the
        // persistent proxy flag off so the proxy service never auto-starts even
        // if it was enabled by a previous build of the app.
        try {
            sharedPrefHelper.setIsProxyOn(false)
        } catch (_: Throwable) { /* defensive: pref helper not ready */ }

        val file: File = fileUtil.folderDir
        val ctx = applicationContext

        WorkManager.initialize(
            ctx, Configuration.Builder().setWorkerFactory(workerFactory).build()
        )

        RxJavaPlugins.setErrorHandler { error: Throwable? ->
            AppLogger.e("RxJavaError unhandled $error")
        }

        CoroutineScope(Dispatchers.Default).launch {
            if (!file.exists()) {
                file.mkdirs()
            }

            initializeYoutubeDl()
            updateYoutubeDL()
        }
    }

    private fun initializeFileUtils() {
        val isExternal = sharedPrefHelper.getIsExternalUse()
        val isAppDir = sharedPrefHelper.getIsAppDirUse()

        FileUtil.IS_EXTERNAL_STORAGE_USE = isExternal
        FileUtil.IS_APP_DATA_DIR_USE = isAppDir
        FileUtil.OVERRIDE_DOWNLOAD_PATH = sharedPrefHelper.getCustomDownloadFolderPath()
        FileUtil.OVERRIDE_DOWNLOAD_TREE_URI = sharedPrefHelper.getCustomDownloadFolderUri()
        FileUtil.INITIIALIZED = true
    }

    /**
     * Re-applies any previously saved per-app locale at every cold start.
     *
     * On Android 13+ the system already restores the chosen locale automatically (the manifest
     * service is disabled in that case). On Android 12 and below the AppCompat backport handles
     * persistence through `autoStoreLocales`, but we still apply our SharedPrefs value as a
     * safety net so previously saved values survive this upgrade.
     *
     * When nothing is saved, we deliberately do **not** force a default locale here. Forcing
     * `setApplicationLocales` at every cold start triggers a configuration change/activity
     * recreation, and would also override the user's system locale on devices that have never
     * picked a language in-app. The Settings picker takes care of presenting "English" as the
     * default highlighted choice in the UI.
     */
    private fun applySavedAppLocaleIfNeeded() {
        try {
            val current = AppCompatDelegate.getApplicationLocales()
            if (!current.isEmpty) return

            val tag = sharedPrefHelper.getAppLanguageTag()
            if (tag.isBlank()) return

            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        } catch (e: Throwable) {
            AppLogger.e("Unable to apply saved app locale: ${e.message}")
        }
    }

    private fun initializeYoutubeDl() {
        try {
            YoutubeDL.getInstance().init(applicationContext)
            FFmpeg.getInstance().init(applicationContext)
        } catch (e: YoutubeDLException) {
            AppLogger.e("failed to initialize youtubedl-android $e")
        }
    }

    private fun updateYoutubeDL() {
        try {
            val status = YoutubeDL.getInstance()
                .updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel._STABLE)
            AppLogger.d("UPDATE_STATUS MASTER: $status")
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    fun startProxyService() {
        val isProxyOn = sharedPrefHelper.getIsProxyOn()
        val isDohOn = sharedPrefHelper.getIsDohOn()
        if (isProxyServiceStarted || !(isProxyOn || isDohOn)) {
            AppLogger.i("Proxy service is already running or not enabled")
            return
        }
        AppLogger.i("Proxy service is starting...")

        val serviceIntent = Intent(this, ProxyService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            isProxyServiceStarted = true
        } catch (e: Throwable) {
            AppLogger.e("Failed to start ProxyService: ${e.message}")
        }
    }

}
