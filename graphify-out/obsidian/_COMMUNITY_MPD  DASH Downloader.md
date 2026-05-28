---
type: community
cohesion: 0.90
members: 23
---

# MPD / DASH Downloader

**Cohesion:** 0.90 - tightly connected
**Members:** 23 nodes

## Members
- [[.addCommonMergeArguments()]] - code - util/downloaders/super_x_downloader/strategy/MpdDownloader.kt
- [[.addCommonMergeArguments()_1]] - code - util/downloaders/super_x_downloader/strategy/MpdLiveDownloader.kt
- [[.download()_1]] - code - util/downloaders/super_x_downloader/SegmentDownloader.kt
- [[.download()_5]] - code - util/downloaders/super_x_downloader/strategy/MpdDownloader.kt
- [[.download()_6]] - code - util/downloaders/super_x_downloader/strategy/MpdLiveDownloader.kt
- [[.downloadByBaseUrl()]] - code - util/downloaders/super_x_downloader/strategy/MpdDownloader.kt
- [[.downloadBySegments()]] - code - util/downloaders/super_x_downloader/strategy/MpdDownloader.kt
- [[.downloadFileWithCustomDownloader()]] - code - util/downloaders/super_x_downloader/strategy/MpdDownloader.kt
- [[.downloadInitSegments()]] - code - util/downloaders/super_x_downloader/strategy/MpdLiveDownloader.kt
- [[.getMpdRepresentations()]] - code - util/downloaders/super_x_downloader/SuperXDownloaderWorker.kt
- [[.interruptibleDelay()_1]] - code - util/downloaders/super_x_downloader/strategy/MpdLiveDownloader.kt
- [[.manualConcat()]] - code - util/downloaders/super_x_downloader/strategy/MpdDownloader.kt
- [[.manualConcat()_1]] - code - util/downloaders/super_x_downloader/strategy/MpdLiveDownloader.kt
- [[.mergeBaseUrlStreams()]] - code - util/downloaders/super_x_downloader/strategy/MpdDownloader.kt
- [[.mergeBaseUrlStreams()_1]] - code - util/downloaders/super_x_downloader/strategy/MpdLiveDownloader.kt
- [[.mergeCapturedSegments()]] - code - util/downloaders/super_x_downloader/strategy/MpdLiveDownloader.kt
- [[.mergeMpdSegments()]] - code - util/downloaders/super_x_downloader/strategy/MpdDownloader.kt
- [[MpdDownloader]] - code - util/downloaders/super_x_downloader/strategy/MpdDownloader.kt
- [[MpdDownloader.kt]] - code - util/downloaders/super_x_downloader/strategy/MpdDownloader.kt
- [[MpdLiveDownloader]] - code - util/downloaders/super_x_downloader/strategy/MpdLiveDownloader.kt
- [[MpdLiveDownloader.kt]] - code - util/downloaders/super_x_downloader/strategy/MpdLiveDownloader.kt
- [[SegmentDownloader]] - code - util/downloaders/super_x_downloader/SegmentDownloader.kt
- [[SegmentDownloader.kt]] - code - util/downloaders/super_x_downloader/SegmentDownloader.kt

## Live Query (requires Dataview plugin)

```dataview
TABLE source_file, type FROM #community/MPD_/_DASH_Downloader
SORT file.name ASC
```

## Connections to other communities
- 4 edges to [[_COMMUNITY_Community 40]]
- 3 edges to [[_COMMUNITY_SuperX Download Worker]]
- 1 edge to [[_COMMUNITY_Custom File Downloader (Chunks)]]
- 1 edge to [[_COMMUNITY_Community 95]]

## Top bridge nodes
- [[SegmentDownloader]] - degree 7, connects to 2 communities
- [[MpdDownloader]] - degree 10, connects to 1 community
- [[MpdLiveDownloader]] - degree 9, connects to 1 community
- [[.download()_6]] - degree 7, connects to 1 community
- [[.downloadBySegments()]] - degree 5, connects to 1 community