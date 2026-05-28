package com.myAllVideoBrowser.ui.main.progress

import androidx.annotation.VisibleForTesting
import androidx.databinding.ObservableField
import androidx.lifecycle.viewModelScope
//import com.allVideoDownloaderXmaster.OpenForTesting
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.NotificationsHelper
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.custom_downloader.CustomRegularDownloader
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.SuperXDownloader
import com.myAllVideoBrowser.util.downloaders.youtubedl_downloader.YoutubeDlDownloader
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

//@OpenForTesting
class ProgressViewModel @Inject constructor(
    private val fileUtil: FileUtil,
    private val progressRepository: ProgressRepository,
    private val notificationsHelper: NotificationsHelper,
) : BaseViewModel() {
    @VisibleForTesting
    internal val compositeDisposable: CompositeDisposable = CompositeDisposable()

    var progressInfos: ObservableField<List<ProgressInfo>> = ObservableField(emptyList())
    private val executor = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
    private val executor2 = Executors.newFixedThreadPool(1).asCoroutineDispatcher()

    override fun start() {
        downloadProgressStartListen()
    }

    override fun stop() {
        compositeDisposable.clear()
    }

    // wtf??? fix what?
    // TODO: strange, should fix
    fun stopAndSaveDownload(id: Long) {
        val inf = progressInfos.get()?.find { it.downloadId == id }

        if (inf?.videoInfo?.isRegularDownload == false) {
            inf.let {
                if (inf.videoInfo.isDetectedBySuperX) {
                    SuperXDownloader.stopAndSaveDownload(
                        ContextUtils.getApplicationContext(), it
                    )
                } else {
                    YoutubeDlDownloader.stopAndSaveDownload(
                        ContextUtils.getApplicationContext(), it
                    )
                }
            }
        } else {
            inf?.let {
                CustomRegularDownloader.stopAndSaveDownload(
                    ContextUtils.getApplicationContext(), it
                )
            }
        }
    }

    fun cancelDownload(id: Long, removeFile: Boolean) {
        val inf = progressInfos.get()?.find { it.downloadId == id }
        inf?.let { progressInfo ->
            // Dismiss the download notification synchronously. Even though
            // each worker also dismisses on cancel, doing it here means a
            // pending/queued download (whose worker may not have started yet)
            // doesn't briefly resurrect a notification slot.
            notificationsHelper.hideNotification(progressInfo.videoInfo.id.hashCode())

            deleteProgressInfo(progressInfo) { info ->
                if (info.videoInfo.isRegularDownload) {
                    CustomRegularDownloader.cancelDownload(
                        ContextUtils.getApplicationContext(),
                        inf,
                        removeFile
                    )
                } else {
                    info.let {
                        if (inf.videoInfo.isDetectedBySuperX) {
                            SuperXDownloader.cancelDownload(
                                ContextUtils.getApplicationContext(), it, removeFile
                            )
                        } else {
                            YoutubeDlDownloader.cancelDownload(
                                ContextUtils.getApplicationContext(), it, removeFile
                            )
                        }
                    }
                }
                val newList = progressInfos.get()?.filter { it.id != info.id }
                progressInfos.set(newList?.sortedBy { it.id })
            }
        }
    }

    fun pauseDownload(id: Long) {
        val inf = progressInfos.get()?.find { it.downloadId == id }

        if (inf?.videoInfo?.isRegularDownload == true) {
            CustomRegularDownloader.pauseDownload(ContextUtils.getApplicationContext(), inf)
        } else {
            val updated = inf?.copy(downloadStatus = VideoTaskState.PAUSE)
            if (updated != null) {
                saveProgressInfo(updated) { info ->
                    if (inf.videoInfo.isDetectedBySuperX) {
                        SuperXDownloader.pauseDownload(ContextUtils.getApplicationContext(), info)
                    } else {
                        YoutubeDlDownloader.pauseDownload(
                            ContextUtils.getApplicationContext(),
                            info
                        )
                    }
                }
            }
        }
    }

    fun resumeDownload(id: Long) {
        val inf = progressInfos.get()?.find { it.downloadId == id }

        if (inf?.videoInfo?.isRegularDownload == true) {
            CustomRegularDownloader.resumeDownload(ContextUtils.getApplicationContext(), inf)
        } else {
            inf?.let {
                val updated = inf.copy(downloadStatus = VideoTaskState.PREPARE)

                saveProgressInfo(updated) { info ->
                    if (inf.videoInfo.isDetectedBySuperX) {
                        SuperXDownloader.resumeDownload(
                            ContextUtils.getApplicationContext(),
                            info
                        )
                    } else {
                        YoutubeDlDownloader.resumeDownload(
                            ContextUtils.getApplicationContext(),
                            info
                        )
                    }
                }
            }
        }
    }

    /**
     * Pause every download that is currently active. Triggered from the
     * top-bar overflow menu ("Pause all").
     */
    fun pauseAllDownloads() {
        val active = progressInfos.get().orEmpty().filter {
            it.downloadStatus == VideoTaskState.DOWNLOADING ||
                    it.downloadStatus == VideoTaskState.PREPARE ||
                    it.downloadStatus == VideoTaskState.START ||
                    it.downloadStatus == VideoTaskState.PENDING
        }
        active.forEach { pauseDownload(it.downloadId) }
    }

    /**
     * Resume / kick off every paused download. Triggered from "Download all".
     */
    fun resumeAllDownloads() {
        val paused = progressInfos.get().orEmpty().filter {
            it.downloadStatus == VideoTaskState.PAUSE ||
                    it.downloadStatus == VideoTaskState.ERROR ||
                    it.downloadStatus == VideoTaskState.ENOSPC
        }
        paused.forEach { resumeDownload(it.downloadId) }
    }

    /**
     * Cancel and remove every download tracked on the page (with their
     * partial files). Caller is responsible for warning the user first.
     */
    fun cancelAllDownloads(removeFiles: Boolean) {
        val all = progressInfos.get().orEmpty().toList()
        all.forEach { cancelDownload(it.downloadId, removeFiles) }
    }

    /**
     * Cancel and remove an explicit list of downloads — used by the multi-select
     * batch-delete flow.
     */
    fun cancelDownloads(ids: Collection<Long>, removeFiles: Boolean) {
        ids.forEach { cancelDownload(it, removeFiles) }
    }

    fun downloadVideo(videoInfo: VideoInfo?) {
        val context = ContextUtils.getApplicationContext()

        videoInfo?.let {
            if (!fileUtil.folderDir.exists() && !fileUtil.folderDir.mkdirs()) {
                return
            }

            val downloadId = videoInfo.id.hashCode().toLong()
            val progressInfo = ProgressInfo(
                id = videoInfo.id,
                downloadId = downloadId,
                videoInfo = videoInfo,
                isM3u8 = videoInfo.isM3u8
            )

            saveProgressInfo(progressInfo) { info ->
                if (info.videoInfo.isRegularDownload) {
                    CustomRegularDownloader.startDownload(context, info.videoInfo)
                } else {
                    if (info.videoInfo.isDetectedBySuperX) {
                        SuperXDownloader.startDownload(context, info.videoInfo)
                    } else {
                        YoutubeDlDownloader.startDownload(context, info.videoInfo)
                    }
                }
            }
        }
    }

    private fun saveProgressInfo(
        progressInfo: ProgressInfo,
        onSuccess: (ProgressInfo) -> Unit = {}
    ) {
        viewModelScope.launch(executor2) {
            progressRepository.saveProgressInfo(progressInfo)
            onSuccess(progressInfo)
        }
    }

    private fun deleteProgressInfo(
        progressInfo: ProgressInfo,
        onSuccess: (ProgressInfo) -> Unit = {}
    ) {
        viewModelScope.launch(executor2) {
            progressRepository.deleteProgressInfo(progressInfo)
            onSuccess(progressInfo)
        }
    }

    @VisibleForTesting
    internal fun downloadProgressStartListen() {
        viewModelScope.launch(executor) {
            progressObservable().doOnError {
                it.printStackTrace()
            }.blockingForEach { progressInfoList ->
                progressInfos.set(progressInfoList.sortedBy { it.id })
            }
        }
    }

    private fun progressObservable(): Observable<List<ProgressInfo>> {
        val youtubeDlDownloads = Observable.interval(1000, TimeUnit.MILLISECONDS).flatMap {
            progressRepository.getProgressInfos().take(1).flatMap {
                val filtered = it.filter { info -> info.downloadStatus != VideoTaskState.SUCCESS }
                Observable.just(filtered).toFlowable(BackpressureStrategy.LATEST).take(1)
            }.toObservable().doOnError { error ->
                error.printStackTrace()
            }
        }

        return youtubeDlDownloads
    }
}
