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

    /**
     * True when the currently-loaded page is YouTube (or a subdomain such as
     * `m.youtube.com`, `music.youtube.com`, or `youtu.be`). Downloading from
     * YouTube is blocked for Google Play policy reasons, so the floating
     * download FAB shows a faded download icon (no loading dots) and a tap
     * surfaces a warning instead of running detection.
     */
    fun isYoutubeBlocked(): ObservableBoolean
}
