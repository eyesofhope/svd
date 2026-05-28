package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import android.webkit.CookieManager
import androidx.databinding.Observable
import androidx.databinding.Observable.OnPropertyChangedCallback
import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import androidx.databinding.ObservableInt
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.model.VideoInfoWrapper
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.VideoRepository
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.ui.main.home.browser.BrowserFragment
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonState
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateCanNotDownload
import com.myAllVideoBrowser.ui.main.home.browser.DownloadButtonStateLoading
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabViewModel
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.CookieUtils
import com.myAllVideoBrowser.util.SingleLiveEvent
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import java.net.HttpCookie
import java.net.URL
import java.util.concurrent.Executors
import javax.inject.Inject
import androidx.core.net.toUri
import android.os.Handler
import android.os.Looper

open class VideoDetectionTabViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val baseSchedulers: BaseSchedulers,
    private val okHttpProxyClient: OkHttpProxyClient,
) : BaseViewModel(), IVideoDetector {
    // key: videoInfo.id, value: format - string
    val selectedFormats = ObservableField<Map<String, String>>()

    // key: videoInfo.id, value: title - string
    val formatsTitles = ObservableField<Map<String, String>>()

    val selectedFormatUrl = ObservableField<String>()

    var initialUrl: String = ""

    @Volatile
    var m3u8LoadingList = ObservableField<MutableSet<String>>()

    @Volatile
    var regularLoadingList = ObservableField<MutableSet<String>>()

    val showDetectedVideosEvent = SingleLiveEvent<Void?>()

    val videoPushedEvent = SingleLiveEvent<Void?>()

    @Volatile
    var downloadButtonState =
        ObservableField<DownloadButtonState>(DownloadButtonStateCanNotDownload())

    val executorReload = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    var webTabModel: WebTabViewModel? = null
    lateinit var settingsModel: SettingsViewModel
    val detectedVideosList = ObservableField(setOf<VideoInfo>())

    val filterRegex =
        Regex("^(.*\\.(apk|html|xml|ico|css|js|png|gif|json|jpg|jpeg|svg|woff|woff2|m3u8|mpd|ts|php|ttf|otf|eot|cur|webp|bmp|tif|tiff|psd|ai|eps|pdf|doc|docx|xls|xlsx|ppt|pptx|csv|md|rtf|vtt|srt|swf|jar|log|txt|m4s))?$")
    val downloadButtonIcon = ObservableInt(R.drawable.invisible_24px)

    /**
     * True once detection on the current page has "settled": at least one
     * video has been pushed AND no new videos have arrived for [settleWindowMs]
     * AND no link-verification or regular-content checks are still in flight.
     *
     * The floating download FAB binds to this so it can show a loading
     * animation until every quality the page exposes has been collected.
     */
    private val _isDetectionSettled = ObservableBoolean(false)

    private val settleWindowMs = 2_000L
    private val settleHandler = Handler(Looper.getMainLooper())
    private val settleRunnable = Runnable {
        val list = detectedVideosList.get()
        val anyChecksRunning =  
            (m3u8LoadingList.get()?.isNotEmpty() == true) ||
                    (regularLoadingList.get()?.isNotEmpty() == true)
        if (!list.isNullOrEmpty() && !anyChecksRunning) {
            _isDetectionSettled.set(true)
        } else if (!list.isNullOrEmpty()) {
            // checks still in flight, try again shortly
            settleHandler.removeCallbacks(scheduledSettle)
            settleHandler.postDelayed(scheduledSettle, settleWindowMs)
        }
    }
    private val scheduledSettle: Runnable get() = settleRunnable

    private fun bumpSettleTimer() {
        _isDetectionSettled.set(false)
        settleHandler.removeCallbacks(settleRunnable)
        settleHandler.postDelayed(settleRunnable, settleWindowMs)
    }

    @Volatile
    var verifyVideoLinkJobStorage = mutableMapOf<String, Disposable>()

    private val hasCheckLoadingsM3u8 = ObservableBoolean(false)
    private val hasCheckLoadingsRegular = ObservableBoolean(false)

    private val executorRegular = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Volatile
    private var lastUrl = ""

    private val regularLoadingListCallback = object : OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            val notEmpty = regularLoadingList.get()?.isNotEmpty() == true
            hasCheckLoadingsRegular.set(notEmpty)
            if (notEmpty) {
                setButtonState(DownloadButtonStateCanNotDownload())
            }
        }
    }

    private val m3u8LoadingListCallback = object : OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            val notEmpty = m3u8LoadingList.get()?.isNotEmpty() == true
            hasCheckLoadingsM3u8.set(notEmpty)
            if (notEmpty) {
                setButtonState(DownloadButtonStateCanNotDownload())
            }
        }
    }

    private val downloadButtonStateCallback = object : OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            when (downloadButtonState.get()) {
                is DownloadButtonStateCanNotDownload -> downloadButtonIcon.set(R.drawable.refresh_24px)
                is DownloadButtonStateCanDownload -> downloadButtonIcon.set(R.drawable.ic_download_24dp)
                is DownloadButtonStateLoading -> {
                    downloadButtonIcon.set(R.drawable.invisible_24px)
                }

                null -> {
                    downloadButtonIcon.set(R.drawable.refresh_24px)
                }
            }
        }
    }

    override fun start() {
        AppLogger.d("START")
        regularLoadingList.addOnPropertyChangedCallback(regularLoadingListCallback)
        m3u8LoadingList.addOnPropertyChangedCallback(m3u8LoadingListCallback)
        downloadButtonState.addOnPropertyChangedCallback(downloadButtonStateCallback)

        downloadButtonStateCallback.onPropertyChanged(null, 0)
    }

    override fun stop() {
        AppLogger.d("STOP")
        regularLoadingList.removeOnPropertyChangedCallback(regularLoadingListCallback)
        m3u8LoadingList.removeOnPropertyChangedCallback(m3u8LoadingListCallback)
        downloadButtonState.removeOnPropertyChangedCallback(downloadButtonStateCallback)
        settleHandler.removeCallbacks(settleRunnable)
        cancelAllCheckJobs()
    }

    override fun onStartPage(url: String, userAgentString: String) {
        if (url == lastUrl) {
            AppLogger.d("onStartPage: URL is the same. Not clearing list.")
            return
        }
        lastUrl = url
        downloadButtonState.set(DownloadButtonStateCanNotDownload())
        _isDetectionSettled.set(false)
        settleHandler.removeCallbacks(settleRunnable)

        if (url != initialUrl) {
            AppLogger.d("onStartPage: URL is not initial url. Clearing list.")
            detectedVideosList.set(mutableSetOf())
            cancelAllCheckJobs()
        } else {
            AppLogger.d("onStartPage: URL is initial url. Skipped clearing list.")
        }

        val req = getRequestWithHeadersForUrl(
            url, url, userAgentString
        )?.build()

        if (req != null) {
            verifyLinkStatus(req)
        }
    }

    fun onReloadPage(url: String, userAgentString: String) {
        lastUrl = url
        downloadButtonState.set(DownloadButtonStateCanNotDownload())
        _isDetectionSettled.set(false)
        settleHandler.removeCallbacks(settleRunnable)

        detectedVideosList.set(mutableSetOf())
        cancelAllCheckJobs()

        val req = getRequestWithHeadersForUrl(
            url, url, userAgentString
        )?.build()

        if (req != null) {
            verifyLinkStatus(req)
        }
    }

    override fun hasCheckLoadingsRegular(): ObservableBoolean {
        return hasCheckLoadingsRegular
    }

    override fun hasCheckLoadingsM3u8(): ObservableBoolean {
        return hasCheckLoadingsM3u8
    }

    override fun isDetectionSettled(): ObservableBoolean {
        return _isDetectionSettled
    }

    override fun showVideoInfo() {
        AppLogger.d("SHOW")
        val state = downloadButtonState.get()

        if (state is DownloadButtonStateCanNotDownload) {
            webTabModel?.getTabTextInput()?.get()?.let {
                if (it.startsWith("http")) {
                    viewModelScope.launch(executorRegular) {
                        onReloadPage(
                            it.trim(),
                            webTabModel?.userAgent?.get() ?: BrowserFragment.MOBILE_USER_AGENT
                        )
                    }
                }
            }
        }

        if (detectedVideosList.get()?.isNotEmpty() == true) {
            showDetectedVideosEvent.call()
        }
    }

    override fun verifyLinkStatus(
        resourceRequest: Request, hlsTitle: String?, isM3u8: Boolean, isMpd: Boolean
    ) {
        if (resourceRequest.url.toString().contains("tiktok.")) {
            return
        }

        val urlToVerify = resourceRequest.url.toString()
        if (isM3u8 || isMpd) {
            startVerifyProcess(resourceRequest, isM3u8, isMpd, hlsTitle)
        } else {
            if (urlToVerify.contains(
                    ".txt"
                )
            ) {
                return
            }
            if (settingsModel.getIsFindVideoByUrl().get()) {
                startVerifyProcess(resourceRequest, isM3u8 = false, isMpd = false)
            }
        }
    }

    open fun startVerifyProcess(
        resourceRequest: Request, isM3u8: Boolean, isMpd: Boolean, hlsTitle: String? = null
    ) {
        val taskUrl = resourceRequest.url.toString().trim()

        val job = verifyVideoLinkJobStorage[taskUrl]
        if (job != null && !job.isDisposed || taskUrl.isEmpty()) {
            return
        }

        val loadings = m3u8LoadingList.get()?.toMutableSet()
        loadings?.add(resourceRequest.url.toString())
        m3u8LoadingList.set(loadings?.toMutableSet())
        if (detectedVideosList.get()?.isEmpty() == true) {
            setButtonState(DownloadButtonStateLoading())
        }

        verifyVideoLinkJobStorage[taskUrl] =
            io.reactivex.rxjava3.core.Observable.create { emitter ->
                val info = try {
                    val isUseLegacyDetection = settingsModel.isUseLegacyM3u8Detection.get()
                    // Default flow is video-only. Audio detection happens lazily
                    // when the user switches the Audio tab in the download popup.
                    if (!isUseLegacyDetection && (isM3u8 || isMpd)) {
                        videoRepository.getVideoInfoBySuperXDetector(
                            resourceRequest, isM3u8, isMpd, false
                        )
                    } else {
                        videoRepository.getVideoInfo(
                            resourceRequest, false, false
                        )
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    null
                }
                if (info != null) {
                    emitter.onNext(info)
                } else {
                    emitter.onNext(VideoInfo(id = ""))
                }
                emitter.onComplete()
            }.doOnTerminate {
                val loadings2 = m3u8LoadingList.get()?.toMutableSet()
                loadings2?.remove(resourceRequest.url.toString())
                m3u8LoadingList.set(loadings2?.toMutableSet())
                verifyVideoLinkJobStorage.remove(taskUrl)
            }.observeOn(baseSchedulers.computation).subscribeOn(baseSchedulers.videoService)
                .subscribe { info ->
                    if (info.id.isNotEmpty()) {
                        if (info.isM3u8 && !hlsTitle.isNullOrEmpty()) {
                            info.title = hlsTitle
                        }
                        pushNewVideoInfoToAll(info)
                    } else if (info.id.isEmpty()) {
                        setButtonState(DownloadButtonStateCanNotDownload())
                    }
                }
    }

    /** Audio resolution state per detected video id (true = currently fetching). */
    val audioResolveLoading = ObservableField<Set<String>>(emptySet())

    /**
     * Cache of audio-only formats per video id. Populated lazily when the user
     * switches to the Audio tab in the download popup so we don't pay this cost
     * during regular browsing.
     */
    val audioFormatsByVideoId = ObservableField<Map<String, List<VideoFormatEntity>>>(emptyMap())

    /**
     * Lazily resolve audio formats for a detected video. Idempotent and safe to
     * call multiple times: returns immediately if already cached or in flight.
     */
    fun resolveAudioFormatsAsync(videoInfo: VideoInfo) {
        val current = audioFormatsByVideoId.get().orEmpty()
        if (current[videoInfo.id]?.isNotEmpty() == true) return

        val loading = audioResolveLoading.get().orEmpty()
        if (videoInfo.id in loading) return

        // Only manifest-based videos have separate audio renditions worth resolving.
        if (!(videoInfo.isM3u8 || videoInfo.isMpd) && !videoInfo.isDetectedBySuperX) {
            // For regular MP4s, surface the muxed source as a single "extract" entry.
            val first = videoInfo.formats.formats.firstOrNull()
            if (first != null) {
                val virtual = listOf(
                    VideoFormatEntity(
                        formatId = "extract-${first.formatId.orEmpty()}",
                        format = "extract-audio",
                        formatNote = "Extract from video (M4A)",
                        ext = "m4a",
                        vcodec = "none",
                        acodec = first.acodec ?: "unknown",
                        url = first.url,
                        manifestUrl = first.manifestUrl,
                        audioOnlyUrl = first.url,
                        httpHeaders = first.httpHeaders,
                        fileSize = 0L
                    )
                )
                audioFormatsByVideoId.set(current + (videoInfo.id to virtual))
            } else {
                audioFormatsByVideoId.set(current + (videoInfo.id to emptyList()))
            }
            return
        }

        val manifestUrl = videoInfo.formats.formats.firstOrNull()?.manifestUrl
            ?: videoInfo.firstUrlToString
        if (manifestUrl.isBlank()) return

        audioResolveLoading.set(loading + videoInfo.id)

        val req = Request.Builder().url(manifestUrl).also { b ->
            videoInfo.formats.formats.firstOrNull()?.httpHeaders?.let { headers ->
                if (headers.isNotEmpty()) b.headers(headers.toHeaders())
            }
        }.build()

        io.reactivex.rxjava3.core.Observable.fromCallable {
            videoRepository.resolveAudioFormats(req, videoInfo.isM3u8, videoInfo.isMpd)
        }
            .subscribeOn(baseSchedulers.io)
            .observeOn(baseSchedulers.mainThread)
            .subscribe({ result ->
                val updated = audioFormatsByVideoId.get().orEmpty() + (videoInfo.id to result)
                audioFormatsByVideoId.set(updated)
                audioResolveLoading.set(audioResolveLoading.get().orEmpty() - videoInfo.id)
            }, { err ->
                AppLogger.e("Audio resolution failed: ${err.message}")
                val updated =
                    audioFormatsByVideoId.get().orEmpty() + (videoInfo.id to emptyList())
                audioFormatsByVideoId.set(updated)
                audioResolveLoading.set(audioResolveLoading.get().orEmpty() - videoInfo.id)
            })
    }

    @Synchronized
    open fun pushNewVideoInfoToAll(newInfo: VideoInfo) {
        if (newInfo.formats.formats.isEmpty()) {
            return
        }

        if (newInfo.id.isEmpty()) {
            return
        }

        val detectedVideos = detectedVideosList.get() ?: emptySet()

        // 1) Apply page thumbnail if we don't have one yet
        applyPageThumbnail(newInfo)

        // 2) Smart consolidation: try to merge this entry into an existing card
        //    instead of producing a new one. We collapse:
        //    - HLS media-playlist children into their master
        //    - duplicate masters / regular MP4s that point at the same media
        //    - small teaser MP4s alongside an existing rich (HLS/MPD) entry
        val merged = detectedVideos.firstNotNullOfOrNull { existing ->
            tryMerge(existing, newInfo)?.let { existing to it }
        }
        if (merged != null) {
            val (oldVideo, mergedVideo) = merged
            val updated = (detectedVideos - oldVideo) + mergedVideo
            detectedVideosList.set(updated)
            setButtonState(DownloadButtonStateCanDownload(mergedVideo))
            bumpSettleTimer()

            viewModelScope.launch(Dispatchers.Main) {
                videoPushedEvent.call()
            }
            return
        }

        // 3) Drop obvious teasers when we already have a richer source
        if (shouldSkipAsTeaser(newInfo, detectedVideos)) {
            AppLogger.d("Skipping teaser candidate: ${newInfo.firstUrlToString}")
            return
        }

        AppLogger.d("PUSHING $newInfo to list: \n  $detectedVideos")
        // 4) When a fresh entry lands, sweep out any pre-existing siblings we
        //    now recognise as ads or stragglers based on duration / format
        //    count. This catches the case where the ad arrives BEFORE the real
        //    video and would otherwise sit alongside it.
        val combined = detectedVideos + newInfo
        val nextList = combined.filterNot { existing ->
            existing !== newInfo && shouldSkipAsTeaser(existing, combined - existing)
        }.toSet()
        detectedVideosList.set(nextList)
        setButtonState(DownloadButtonStateCanDownload(newInfo))
        bumpSettleTimer()

        viewModelScope.launch(Dispatchers.Main) {
            videoPushedEvent.call()
        }
    }

    private fun applyPageThumbnail(info: VideoInfo) {
        if (info.thumbnail.isNotBlank()) return
        val thumb = webTabModel?.pageThumbnailUrl?.get().orEmpty()
        if (thumb.startsWith("http")) {
            info.thumbnail = thumb
        }
    }

    /**
     * Attempt to merge [incoming] into [existing] returning the merged entity or
     * null if they should remain separate. The merge prefers the richer source
     * (more formats, manifest-based detection, has thumbnail) and unions any
     * formats not already present.
     */
    private fun tryMerge(existing: VideoInfo, incoming: VideoInfo): VideoInfo? {
        if (!isSameMedia(existing, incoming)) return null

        // Build the merged formats list, preferring the richer entry's set
        val left = if (existing.formats.formats.size >= incoming.formats.formats.size) existing else incoming
        val right = if (left === existing) incoming else existing

        val seen = left.formats.formats.map { it.url ?: it.format }.toMutableSet()
        val merged = left.formats.formats.toMutableList()
        for (f in right.formats.formats) {
            val key = f.url ?: f.format
            if (key != null && seen.add(key)) {
                merged.add(f)
            }
        }

        val base = if (left.isDetectedBySuperX || !right.isDetectedBySuperX) left else right
        return base.copy(
            formats = VideFormatEntityList(merged),
            thumbnail = base.thumbnail.ifBlank { right.thumbnail.ifBlank { left.thumbnail } },
            title = base.title.ifBlank { right.title.ifBlank { left.title } }
        )
    }

    /**
     * Two VideoInfos refer to the same underlying media when:
     *  - One is an HLS/MPD parent and the other is a child media playlist of it
     *  - They share at least one identical format URL
     *  - They are both regular MP4s with the same URL stem (host + path before query)
     *  - They are sibling HLS media playlists of the SAME video (same parent
     *    folder + roughly same duration). This catches sites that ship a
     *    separate media playlist per quality without ever exposing the master.
     */
    private fun isSameMedia(a: VideoInfo, b: VideoInfo): Boolean {
        val aEff = effectiveUrl(a)
        val bEff = effectiveUrl(b)

        // Same exact URL
        if (aEff.isNotEmpty() && aEff == bEff) return true

        // Any shared format URL (same variant referenced by both)
        val aUrls = a.formats.formats.mapNotNull { it.url }.toSet()
        val bUrls = b.formats.formats.mapNotNull { it.url }.toSet()
        if (aUrls.any { it in bUrls }) return true

        // HLS/MPD child arrived after the master: master.variants[i].url ==
        // child.originalUrl. The master entry exposes those variant URLs in
        // its videoOnlyUrl/url fields, so check both directions.
        val aVariantUrls = a.formats.formats.flatMap { listOfNotNull(it.url, it.videoOnlyUrl) }.toSet()
        val bVariantUrls = b.formats.formats.flatMap { listOfNotNull(it.url, it.videoOnlyUrl) }.toSet()
        if (a.originalUrl.isNotEmpty() && a.originalUrl in bVariantUrls) return true
        if (b.originalUrl.isNotEmpty() && b.originalUrl in aVariantUrls) return true

        // HLS/MPD child of the same master via manifestUrl
        val aManifest = a.formats.formats.firstOrNull()?.manifestUrl
        val bManifest = b.formats.formats.firstOrNull()?.manifestUrl
        if (!aManifest.isNullOrBlank() && aManifest == bManifest) return true

        // Sibling HLS / MPD media playlists. Two manifest entries belong to
        // the same video if they live in the same parent directory. This is
        // the common case on sites that don't expose a master playlist
        // (otherwise we see one card per quality). For HLS children,
        // downloadUrls is empty so we MUST read the URL from originalUrl
        // rather than firstUrlToString.
        //
        // Duration alone is too brittle as a tiebreaker: per-quality
        // playlists on the same CDN sometimes round to slightly different
        // totals, and ad insertions change it dramatically. Instead, we
        // compare parent path + the heuristic that both URLs contain a
        // recognisable resolution segment (e.g. ".../1080p.mp4.m3u8" /
        // ".../720p.mp4.m3u8"). Same parent + different recognised
        // resolutions is a near-certain sibling pair.
        if (!a.isRegularDownload && !b.isRegularDownload) {
            val aParent = parentPath(aEff)
            val bParent = parentPath(bEff)
            if (aParent.isNotEmpty() && aParent == bParent) {
                // If durations are both known, allow up to 5s drift.
                val durationDelta = kotlin.math.abs(a.duration - b.duration)
                if (a.duration > 0 && b.duration > 0 && durationDelta < 5_000) {
                    return true
                }
                // Fallback: if either duration is unknown but the URLs both
                // expose a resolution token, treat them as siblings of the
                // same media.
                val aRes = inferHeightFromUrl(aEff)
                val bRes = inferHeightFromUrl(bEff)
                if (aRes != null && bRes != null) return true
            }
        }

        // Same path stem (covers ad-server and CDN ?token= variants)
        val aStem = stripQuery(aEff)
        val bStem = stripQuery(bEff)
        if (aStem.isNotEmpty() && aStem == bStem) return true

        return false
    }

    /**
     * Resolve the most useful URL identifier for an entry. For HLS / MPD
     * child playlists detected by SuperX the `downloadUrls` list is empty
     * (the manifest content was passed by reference, not by URL), so
     * `firstUrlToString` is "". The actual playlist URL lives in
     * `originalUrl`. Falling back to it lets the parent-path / stem checks
     * actually fire for sibling-merge cases.
     */
    private fun effectiveUrl(info: VideoInfo): String {
        val first = info.firstUrlToString
        if (first.isNotEmpty()) return first
        if (info.originalUrl.isNotEmpty()) return info.originalUrl
        // As a last resort, look at the first format's URL directly.
        return info.formats.formats.firstOrNull()?.url.orEmpty()
    }

    private fun stripQuery(url: String): String =
        url.substringBefore('?').substringBefore('#')

    private fun parentPath(url: String): String {
        val noQuery = stripQuery(url)
        return noQuery.substringBeforeLast('/', "")
    }

    /**
     * Best-effort height extraction from a URL. Recognises forms like
     *   - .../1080p.mp4.m3u8, .../720p/playlist.m3u8
     *   - .../1920x1080/index.m3u8
     *   - .../hls-480p-12345.m3u8
     */
    private fun inferHeightFromUrl(url: String): Int? {
        val cleaned = stripQuery(url).lowercase()
        val pRegex = Regex("(\\d{2,4})p[^/]*$")
        pRegex.find(cleaned)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val xRegex = Regex("\\d{2,4}x(\\d{2,4})")
        xRegex.findAll(cleaned).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { return it }
        return null
    }

    /**
     * Domains that are known to serve ad / tracking video streams on the
     * adult-video sites we target. Anything originating from these hosts is
     * dropped unconditionally so it can never appear as a downloadable card.
     */
    private val adHostBlocklist = listOf(
        "tsyndicate.com",
        "trafficstars.com",
        "trafficjunky.com",
        "trafficjunky.net",
        "exoclick.com",
        "exosrv.com",
        "doppiocdn.com",
        "juicyads.com",
        "ero-advertising.com",
        "popads.net",
        "popcash.net",
        "adsterra.com",
        "googlesyndication.com",
        "doubleclick.net",
    )

    private fun isAdHost(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        return adHostBlocklist.any { host == it || host.endsWith(".$it") }
    }

    /**
     * If we already have an HLS/MPD-detected video for this page, ignore tiny
     * regular MP4 candidates and short ad streams.
     *
     * The strongest signals that something is an ad are:
     *   1) the host is on the known ad-network blocklist
     *   2) the duration is short (≤ 60s) AND another candidate on the page
     *      is meaningfully longer
     *   3) it is a tiny regular MP4 sitting next to any other source.
     */
    private fun shouldSkipAsTeaser(
        newInfo: VideoInfo, existing: Set<VideoInfo>
    ): Boolean {
        // 0) Always skip well-known ad hosts. We check both the originalUrl
        //    and any per-format URL so this catches both manifest-level and
        //    variant-level matches.
        val candidateUrls = buildList {
            add(newInfo.originalUrl)
            add(newInfo.firstUrlToString)
            newInfo.formats.formats.forEach {
                add(it.url.orEmpty())
                add(it.manifestUrl.orEmpty())
            }
        }.filter { it.isNotEmpty() }
        if (candidateUrls.any { isAdHost(it) }) {
            AppLogger.d("Skipping ad-host candidate: ${candidateUrls.firstOrNull()}")
            return true
        }

        val newDuration = newInfo.duration

        // 1) Short streams that sit next to a meaningfully longer one are
        //    almost always preroll / interstitial ads.
        if (newDuration in 1..60_000) {
            val longestExisting = existing.maxOfOrNull { it.duration } ?: 0L
            if (longestExisting > newDuration * 2) return true
        }

        // 2) Pure-MP4 candidates with very short duration are ads.
        if (newInfo.isRegularDownload && newDuration in 1..30_000) return true

        // 3) Tiny MP4 candidates beside any other source are almost always ads.
        if (newInfo.isRegularDownload && existing.isNotEmpty()) {
            val size = newInfo.formats.formats.firstOrNull()?.fileSize ?: 0L
            if (size in 1..(1024L * 1024L)) return true
        }

        return false
    }

    override fun getDownloadBtnIcon(): ObservableInt {
        return downloadButtonIcon
    }

    override fun checkRegularVideoOrAudio(
        request: Request?, isCheckOnAudio: Boolean, isCheckOnVideo: Boolean
    ): Disposable? {
        if (request == null) {
            return null
        }

        val uriString = request.url.toString()

        if (!uriString.startsWith("http")) {
            return null
        }

        val clearedUrl = uriString.split("?").first().trim()

        if (clearedUrl.contains(filterRegex)) {
            return null
        }

        val headers = try {
            request.headers.toMap().toMutableMap()
        } catch (_: Throwable) {
            mutableMapOf()
        }

        val disposable = io.reactivex.rxjava3.core.Observable.create<Unit> {
            if (request.url.toString().contains(".mp4")) {
                setButtonState(DownloadButtonStateLoading())
            }
            val loadings = regularLoadingList.get()
            loadings?.add(request.url.toString())
            regularLoadingList.set(loadings?.toMutableSet())
            propagateCheckJob(uriString, headers, isCheckOnAudio, isCheckOnVideo)
            it.onComplete()
        }.subscribeOn(baseSchedulers.io).doOnComplete {
            val loadings = regularLoadingList.get()
            loadings?.remove(request.url.toString())
            regularLoadingList.set(loadings?.toMutableSet())
        }.onErrorComplete().doOnError {
            AppLogger.d("Checking ERROR... $clearedUrl")
        }.subscribe()

        return disposable
    }

    override fun cancelAllCheckJobs() {
        regularLoadingList.set(mutableSetOf())
        m3u8LoadingList.set(mutableSetOf())
        executorRegular.cancel()
        verifyVideoLinkJobStorage.forEach { (_, process) ->
            process.dispose()
        }
        verifyVideoLinkJobStorage.clear()
    }


    @Synchronized
    fun setButtonState(state: DownloadButtonState) {
        when (state) {
            is DownloadButtonStateCanDownload -> {
                downloadButtonState.set(state)
            }

            is DownloadButtonStateCanNotDownload -> {
                val detectedSize = detectedVideosList.get()?.size
                if (detectedSize == null || detectedSize == 0) {
                    downloadButtonState.set(DownloadButtonStateCanNotDownload())
                } else {
                    downloadButtonState.set(
                        DownloadButtonStateCanDownload(
                            detectedVideosList.get()?.first()
                        )
                    )
                }
            }

            is DownloadButtonStateLoading -> {
                val list = detectedVideosList.get() ?: emptySet()
                if (list.isEmpty()) {
                    downloadButtonState.set(DownloadButtonStateLoading())
                } else {
                    downloadButtonState.set(DownloadButtonStateCanDownload(list.first()))
                }
            }
        }
    }

    private fun getRequestWithHeadersForUrl(
        url: String,
        originalUrl: String,
        userAgent: String,
        alternativeHeaders: Map<String, String> = emptyMap()
    ): Request.Builder? {
        try {
            val cookies = try {
                CookieManager.getInstance().getCookie(url) ?: CookieManager.getInstance()
                    .getCookie(originalUrl) ?: ""
            } catch (_: Throwable) {
                ""
            }
            val stringBuilder = StringBuilder()
            if (cookies.isNotEmpty()) {
                for (cookie in cookies.split(";")) {
                    val parsedCookies = HttpCookie.parse(cookie)

                    for (httpCookie in parsedCookies) {
                        stringBuilder.append("${httpCookie.name}=${httpCookie.value};")
                    }
                }
            }

            if (alternativeHeaders.isEmpty()) {
                val builder = try {
                    Request.Builder().url(url.trim())
                } catch (_: Exception) {
                    null
                }
                builder?.addHeader("Referer", "https://${originalUrl.toUri().host}/")

                builder?.addHeader("User-Agent", userAgent)

                try {
                    if (cookies.isNotEmpty()) {
                        builder?.addHeader("Cookie", stringBuilder.toString())
                    }
                } catch (e: Exception) {
                    AppLogger.d("Url parse error ${e.message}")
                }
                return builder

            } else {
                val builder = try {
                    Request.Builder().url(url.trim())
                } catch (_: Exception) {
                    null
                }
                builder?.headers(alternativeHeaders.toHeaders())
                if (cookies.isNotEmpty() && alternativeHeaders["Cookie"] == null) {
                    builder?.addHeader("Cookie", stringBuilder.toString())
                }

                return builder
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        return null
    }

    fun propagateCheckJob(
        url: String,
        headersMap: Map<String, String>,
        isCheckOnAudio: Boolean,
        isCheckOnVideo: Boolean
    ) {
        val threshold = settingsModel.videoDetectionTreshold.get()

        val finalUrlPair = runCatching {
            CookieUtils.getFinalRedirectURL(URL(url.toUri().toString()), headersMap)
        }.getOrNull() ?: return

        val cookies = runCatching {
            CookieManager.getInstance().getCookie(finalUrlPair.first.toString())
                ?: CookieManager.getInstance().getCookie(url) ?: ""
        }.getOrNull() ?: ""

        val headers = headersMap.toMutableMap().apply {
            if (cookies.isNotEmpty()) {
                put("Cookie", cookies)
            }
        }

        runCatching {
            val request =
                Request.Builder().url(finalUrlPair.first).headers(headers.toHeaders()).build()

            okHttpProxyClient.getProxyOkHttpClient().newCall(request).execute().use { response ->
                val contentType = response.body.contentType().toString()
                val contentLength = response.body.contentLength()

                if (response.code == 403 || response.code == 401) {
                    handleUnauthorizedResponse(url, threshold, isCheckOnAudio, isCheckOnVideo)
                    return
                }

                val isTikTok = url.contains(".tiktok.com/")
                val isRegularStreamDetectionOn = settingsModel.isForceStreamDetection.get()

                val isVideo = contentType.contains("video", true)
                val isAudio = contentType.contains("audio", true)

                val tikTokThreshold = 1024 * 1024 / 3 // ~333KB
                val isLargeEnoughForTikTok = isTikTok && contentLength > tikTokThreshold
                val isAboveUserThreshold = contentLength > threshold
                val isStreamDetectionOn = isRegularStreamDetectionOn

                val isVideoContent =
                    isVideo && isCheckOnVideo && (isAboveUserThreshold || isLargeEnoughForTikTok || isStreamDetectionOn)

                val isAudioContent = isAudio && isCheckOnAudio

                if (isVideoContent) {
                    setMediaInfoWrapperFromUrl(
                        finalUrlPair.first,
                        webTabModel?.getTabTextInput()?.get(),
                        finalUrlPair.second.toMap(),
                        contentLength
                    )
                } else if (isAudioContent) {
                    setMediaInfoWrapperFromUrl(
                        finalUrlPair.first,
                        webTabModel?.getTabTextInput()?.get(),
                        finalUrlPair.second.toMap(),
                        contentLength,
                        isAudio = true
                    )
                }
            }
        }.onFailure { e ->
            e.printStackTrace()
        }
    }

    // THIS BULLSHIT NEEDED FOR SOME INDIAN WEB-SITES
    private fun handleUnauthorizedResponse(
        url: String, threshold: Int, isCheckOnAudio: Boolean, isCheckOnVideo: Boolean
    ) {
        val finalUrlPairEmpty = runCatching {
            CookieUtils.getFinalRedirectURL(URL(url.toUri().toString()), emptyMap())
        }.getOrNull() ?: return

        runCatching {
            val request = Request.Builder().url(finalUrlPairEmpty.first).build()
            okHttpProxyClient.getProxyOkHttpClient().newCall(request).execute().use { response ->
                val contentType = response.body.contentType().toString()
                val contentLength = response.body.contentLength()

                when {
                    contentType.contains(
                        "video", true
                    ) && isCheckOnVideo && contentLength > threshold.toLong() -> {
                        setMediaInfoWrapperFromUrl(
                            finalUrlPairEmpty.first,
                            webTabModel?.getTabTextInput()?.get(),
                            finalUrlPairEmpty.second.toMap(),
                            contentLength
                        )
                    }

                    contentType.contains("audio", true) && isCheckOnAudio -> {
                        setMediaInfoWrapperFromUrl(
                            finalUrlPairEmpty.first,
                            webTabModel?.getTabTextInput()?.get(),
                            finalUrlPairEmpty.second.toMap(),
                            contentLength,
                            true
                        )
                    }
                }
            }
        }
    }

    private fun setMediaInfoWrapperFromUrl(
        url: URL,
        originalUrl: String?,
        alternativeHeaders: Map<String, String> = emptyMap(),
        contentLength: Long,
        isAudio: Boolean = false
    ) {
        try {
            if (!url.toString().startsWith("http")) {
                return
            }

            val builder = if (originalUrl != null) {
                Request.Builder().url(url.toString()).headers(alternativeHeaders.toHeaders())
            } else {
                null
            }

            val downloadUrls = listOfNotNull(
                builder?.build()
            )

            val video = VideoInfoWrapper(
                VideoInfo(
                    downloadUrls = downloadUrls,
                    title = webTabModel?.currentTitle?.get() ?: "no_title",
                    ext = if (isAudio) "mp3" else "mp4",
                    originalUrl = webTabModel?.getTabTextInput()?.get() ?: "",
                    // TODO format regular file link
                    formats = VideFormatEntityList(
                        mutableListOf(
                            VideoFormatEntity(
                                formatId = "0",
                                format = if (isAudio) "audio" else ContextUtils.getApplicationContext()
                                    .getString(R.string.player_resolution),
                                ext = if (isAudio) "mp3" else "mp4",
                                url = downloadUrls.first().url.toString(),
                                httpHeaders = downloadUrls.first().headers.toMap(),
                                fileSize = contentLength
                            )
                        )
                    ),
                    isRegularDownload = true
                )
            )
            video.videoInfo?.let { pushNewVideoInfoToAll(it) }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
