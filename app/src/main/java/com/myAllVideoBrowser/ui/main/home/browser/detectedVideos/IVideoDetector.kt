package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableInt
import io.reactivex.rxjava3.disposables.Disposable
import okhttp3.Request

interface IVideoDetector {
    fun onStartPage(url: String, userAgentString: String)

    fun showVideoInfo()

    fun verifyLinkStatus(
        resourceRequest: Request,
        hlsTitle: String? = null,
        isM3u8: Boolean = false,
        isMpd: Boolean = false
    )

    fun getDownloadBtnIcon(): ObservableInt

    fun checkRegularVideoOrAudio(
        request: Request?,
        isCheckOnAudio: Boolean,
        isCheckOnVideo: Boolean
    ): Disposable?

    fun cancelAllCheckJobs()

    fun hasCheckLoadingsRegular(): ObservableBoolean

    fun hasCheckLoadingsM3u8(): ObservableBoolean

    /**
     * True once the detection pipeline on the current page is settled and
     * every quality the page exposes has been collected. Bound by the
     * floating download FAB so it can show a loading animation until then.
     */
    fun isDetectionSettled(): ObservableBoolean
}
