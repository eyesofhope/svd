package com.myAllVideoBrowser.data.remote.service

import com.myAllVideoBrowser.data.local.model.VideoInfoWrapper
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.hls_parser.HlsPlaylistParser
import com.myAllVideoBrowser.util.hls_parser.MpdPlaylistParser
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import java.io.IOException
import java.time.Duration

/**
 * A dedicated service that parses HLS (.m3u8) and MPD (.mpd) manifests
 * to discover available video and audio streams. It uses efficient, in-app
 * parsers instead of FFprobe for speed and reliability.
 */
class VideoServiceSuperX(
    private val client: OkHttpProxyClient
) : VideoService {

    override fun getVideoInfo(
        url: Request, isM3u8: Boolean, isMpd: Boolean,
        isAudioCheck: Boolean
    ): VideoInfoWrapper? {
        if (!(isM3u8 || isMpd)) {
            return null
        }

        return try {
            handlePlaylistUrl(url, isM3u8, isMpd)
        } catch (e: Throwable) {
            AppLogger.d("PlaylistService Error: Failed to parse manifest. ${e.message}")
            null
        }
    }

    /**
     * Resolve audio-only formats from an HLS or MPD manifest. This is invoked
     * lazily, only when the user explicitly switches to the Audio tab in the
     * download popup, so we don't pay the cost during regular video detection.
     */
    fun resolveAudioFormats(
        url: Request, isM3u8: Boolean, isMpd: Boolean
    ): List<VideoFormatEntity> {
        return try {
            val urlString = url.url.toString()
            val response = client.getProxyOkHttpClient().newCall(url).execute()
            val content = response.body.string()
            if (!response.isSuccessful || content.isEmpty()) return emptyList()

            when {
                isM3u8 -> {
                    val manifest = HlsPlaylistParser.parse(content, urlString)
                    if (manifest is HlsPlaylistParser.MasterPlaylist) {
                        parseHlsAudioRenditions(manifest, url.headers.toMap())
                    } else emptyList()
                }
                isMpd -> {
                    val manifest = MpdPlaylistParser.parse(content, urlString)
                    parseMpdAudioRepresentations(manifest, url.headers.toMap())
                }
                else -> emptyList()
            }
        } catch (e: Throwable) {
            AppLogger.d("resolveAudioFormats error: ${e.message}")
            emptyList()
        }
    }

    private fun parseHlsAudioRenditions(
        manifest: HlsPlaylistParser.MasterPlaylist,
        headers: Map<String, String>
    ): List<VideoFormatEntity> {
        return manifest.alternateRenditions
            .filter { it.type == HlsPlaylistParser.RenditionType.AUDIO && !it.url.isNullOrBlank() }
            // Keep one entry per group/name pair (matches the worker's lookup key)
            .distinctBy { "${it.groupId}|${it.name}" }
            .mapIndexed { idx, audio ->
                val labelParts = listOfNotNull(
                    audio.name.ifBlank { null },
                    audio.language?.ifBlank { null }
                )
                val label = labelParts.joinToString(" ").ifBlank { "Audio ${idx + 1}" }
                val bitrateK = if (audio.bandwidth > 0) "${audio.bandwidth / 1000} kbps" else null
                // IMPORTANT: format/formatId must match the worker's expected
                // pattern `hls-audio-<groupId>-<name>` so the downloader can
                // resolve the correct rendition. See SuperXDownloaderWorker.
                val matcherId = "hls-audio-${audio.groupId}-${audio.name}"
                VideoFormatEntity(
                    formatId = matcherId,
                    format = matcherId,
                    formatNote = listOfNotNull(label, bitrateK).joinToString(" · "),
                    ext = "m4a",
                    vcodec = "none",
                    acodec = audio.codecs ?: "unknown",
                    url = audio.url,
                    manifestUrl = manifest.baseUri,
                    audioOnlyUrl = audio.url,
                    httpHeaders = headers,
                    height = 0,
                    width = 0,
                    bitrate = audio.bandwidth
                )
            }
    }

    private fun parseMpdAudioRepresentations(
        manifest: MpdPlaylistParser.MpdManifest,
        headers: Map<String, String>
    ): List<VideoFormatEntity> {
        val audioReps = manifest.periods.flatMap { it.adaptationSets }
            .filter { it.mimeType?.startsWith("audio/") == true }
            .flatMap { it.representations }

        return audioReps.distinctBy { it.bandwidth }.map { rep ->
            val bitrateK = if (rep.bandwidth > 0) "${rep.bandwidth / 1000} kbps" else null
            // Match worker's expected pattern `mpd-audio-<bandwidth>`.
            val matcherId = "mpd-audio-${rep.bandwidth}"
            VideoFormatEntity(
                formatId = matcherId,
                format = matcherId,
                formatNote = listOfNotNull("Audio", bitrateK).joinToString(" · "),
                ext = "m4a",
                vcodec = "none",
                acodec = rep.codecs ?: "unknown",
                url = manifest.baseUri,
                manifestUrl = manifest.baseUri,
                httpHeaders = headers,
                height = 0,
                width = 0,
                bitrate = rep.bandwidth
            )
        }
    }

    /**
     * Fetches the manifest content and delegates parsing to the appropriate
     * HLS or MPD parsing function based on the URL.
     */
    private fun handlePlaylistUrl(
        url: Request,
        isM3u8: Boolean,
        isMpd: Boolean
    ): VideoInfoWrapper? {
        val urlString = url.url.toString()
        AppLogger.d("PlaylistService: Fetching manifest from $urlString")

        // 1. Fetch the manifest content
        val response = client.getProxyOkHttpClient().newCall(url).execute()
        val content = response.body.string()
        AppLogger.d("Manifest body: $content")

        if (!response.isSuccessful || content.isEmpty()) {
            throw IOException("Failed to download playlist at $urlString. HTTP ${response.code}")
        }

        // 2. Determine playlist type and parse
        return if (isM3u8) {
            AppLogger.d("PlaylistService: Detected HLS manifest.")
            val manifest = HlsPlaylistParser.parse(content, urlString)
            parseHlsManifest(manifest, url.headers.toMap())
        } else if (isMpd) {
            AppLogger.d("PlaylistService: Detected MPD manifest.")
            val manifest = MpdPlaylistParser.parse(content, urlString)
            parseMpdManifest(manifest, url.headers.toMap())
        } else {
            AppLogger.w("PlaylistService: URL was flagged as a playlist but extension is not .m3u8 or .mpd.")
            null
        }
    }

    private fun parseHlsManifest(
        manifest: HlsPlaylistParser.HlsPlaylist, headers: Map<String, String>
    ): VideoInfoWrapper? {
        val formats = mutableListOf<VideoFormatEntity>()
        val title: String
        var duration: Long
        val isLive: Boolean

        when (manifest) {
            is HlsPlaylistParser.MasterPlaylist -> {
                title = "HLS Stream"
                // Get duration and live status from the first child playlist
                val firstMediaPlaylist = fetchFirstMediaPlaylist(manifest, headers)
                isLive = firstMediaPlaylist?.hasEndList == false
                duration =
                    if (isLive) 0L else (firstMediaPlaylist?.totalDuration?.times(1000))?.toLong()
                        ?: 0L

                if (manifest.variants.isEmpty()) {
                    AppLogger.d("HLS Parse: Master playlist has no variants.")
                    return null
                }

                // --- Handle Separate Audio and Video ---

                // 1. Find all available audio renditions, grouping them by their GROUP-ID.
                val audioRenditionsByGroup: Map<String, List<HlsPlaylistParser.HlsRendition>> =
                    manifest.alternateRenditions
                        .filter { it.type == HlsPlaylistParser.RenditionType.AUDIO && it.url != null }
                        .groupBy { it.groupId }

                // 2. Process each video variant
                manifest.variants.mapNotNullTo(formats) { variant ->
                    val height = variant.resolution?.split("x")?.getOrNull(1)?.toIntOrNull() ?: 0
                    val width = variant.resolution?.split("x")?.getOrNull(0)?.toIntOrNull() ?: 0

                    // This is a video-only or muxed video variant.
                    if (width > 0 && height > 0) {
                        val videoUrl = variant.url // The URL to the video media playlist

                        // Find the best associated audio rendition using the variant's audio group ID.
                        val audioGroupId = variant.audioGroupId
                        val associatedAudioRendition =
                            audioRenditionsByGroup[audioGroupId]?.firstOrNull()
                        val audioUrl = associatedAudioRendition?.url

                        val combinedBitrate =
                            variant.bandwidth + (associatedAudioRendition?.bandwidth ?: 0)
                        val approxBytes = approxFileSize(combinedBitrate, duration)

                        // The final manifest URL for downloading is the MASTER playlist URL.
                        // The URLs for video/audio tracks will be selected by the downloader later.
                        VideoFormatEntity(
                            formatId = "hls-${height}p-${variant.bandwidth}",
                            format = "hls-${height}p-${variant.bandwidth}",
                            formatNote = "${height}p",
                            ext = "mp4",
                            vcodec = variant.codecs?.substringBefore(",") ?: "unknown",
                            // If we have separate audio, its codec is in the rendition tag.
                            acodec = associatedAudioRendition?.codecs
                                ?: variant.codecs?.substringAfter(",", "unknown") ?: "unknown",
                            // The downloader only needs the MASTER manifest URL.
                            url = manifest.baseUri,
                            manifestUrl = manifest.baseUri,
                            // Store the specific media playlist URLs if needed for later selection.
                            // We will use the formatId to find these again.
                            videoOnlyUrl = videoUrl,
                            audioOnlyUrl = audioUrl,
                            httpHeaders = headers,
                            // Surface the approximated size so the popup can
                            // display it next to the resolution.
                            fileSizeApproximate = approxBytes,
                            height = height,
                            width = width,
                            bitrate = variant.bandwidth + (associatedAudioRendition?.bandwidth
                                ?: 0), // Combined bitrate
                            duration = duration
                        )
                    } else {
                        // This is an audio-only variant, which we can ignore if we are properly
                        // pairing video variants with audio renditions.
                        null
                    }
                }
            }

            is HlsPlaylistParser.MediaPlaylist -> {
                // This logic handles single media playlists (a child of a master
                // played without the master, or a single-quality stream).
                title = "HLS Stream"
                duration = (manifest.totalDuration * 1000).toLong()
                isLive = !manifest.hasEndList

                // Try to infer the resolution from the URL. We support patterns
                // like ".../1080p.mp4.m3u8", ".../720p.av1.mp4.m3u8",
                // ".../1920x1080/index.m3u8", ".../hls-480p-12345.m3u8" etc.
                val inferredHeight = inferHeightFromUrl(manifest.baseUri)

                // Some sites (doppiocdn etc.) only ever expose a single child
                // playlist URL but advertise the full quality ladder inside a
                // ".../multi=...:144p:,240p:,480p:,720p:,1080p:/..." segment.
                // In that case synthesise one selectable VideoFormatEntity per
                // advertised resolution so the user sees the full ladder
                // instead of just the quality the site happened to send.
                val synthesised = synthesizeMultiVariantFormats(
                    manifest = manifest,
                    durationMs = duration,
                    headers = headers
                )

                if (synthesised.isNotEmpty()) {
                    formats.addAll(synthesised)
                } else {
                    // Without a master we don't know the bandwidth, so use a
                    // reasonable per-resolution average to estimate file size.
                    val approxBytes = approxFileSize(
                        bitrate = typicalBitrateForHeight(inferredHeight),
                        durationMs = duration
                    )

                    formats.add(
                        VideoFormatEntity(
                            // Encode the inferred height into the format id so
                            // sibling media playlists for different qualities
                            // (240p / 480p / 720p) don't collapse into the same
                            // entry when they're merged into a single card.
                            formatId = "hls-media-${inferredHeight ?: "unknown"}",
                            format = if (inferredHeight != null) "hls-${inferredHeight}p" else "hls-media",
                            formatNote = if (inferredHeight != null) "${inferredHeight}p" else "Auto",
                            ext = "mp4",
                            vcodec = "unknown",
                            acodec = "unknown",
                            url = manifest.baseUri,
                            manifestUrl = manifest.baseUri,
                            httpHeaders = headers,
                            height = inferredHeight ?: 0,
                            width = 0,
                            fileSizeApproximate = approxBytes,
                            duration = duration
                        )
                    )
                }
            }
        }

        if (formats.isEmpty()) {
            AppLogger.d("HLS Parse: No suitable video formats could be created.")
            return null
        }

        return VideoInfoWrapper(
            VideoInfo(
                title = title,
                originalUrl = manifest.baseUri,
                formats = VideFormatEntityList(formats.sortedByDescending { it.bitrate })
            ).apply {
                this.ext = "mp4"
                this.isRegularDownload = false
                this.duration = duration
                this.isLive = isLive
                this.isDetectedBySuperX = true
            }
        )
    }

    /**
     * Translates a parsed MPD Manifest into a VideoInfoWrapper object
     * containing a list of selectable video formats.
     */
    private fun parseMpdManifest(
        manifest: MpdPlaylistParser.MpdManifest, headers: Map<String, String>
    ): VideoInfoWrapper? {
        // 1. Detect if the stream is live. This is the primary indicator.
        val isLive = manifest.type == "dynamic"

        // 2. Determine duration. For live streams, duration is 0. For VoD, parse it.
        val durationInMillis = if (isLive) {
            0L
        } else {
            parseIso8601Duration(manifest.mediaPresentationDuration)
        }

        // 3. Find all video representations across all periods and adaptation sets.
        val allVideoRepresentations = manifest.periods.flatMap { it.adaptationSets }
            .filter { it.mimeType?.startsWith("video/") == true }
            .flatMap { it.representations }

        if (allVideoRepresentations.isEmpty()) {
            AppLogger.d("MPD Parse: No video representations found.")
            return null
        }

        val formats = allVideoRepresentations.mapNotNull { rep ->
            if (rep.height == 0 || rep.width == 0) return@mapNotNull null

            val approxBytes = approxFileSize(rep.bandwidth, durationInMillis)
            VideoFormatEntity(
                formatId = "mpd-${rep.height}p-${rep.bandwidth}",
                format = "mpd-${rep.height}p-${rep.bandwidth}",
                formatNote = "${rep.height}p",
                ext = "mp4",
                vcodec = rep.codecs?.substringBefore(",") ?: "unknown",
                // A better fallback for acodec in case there is no comma
                acodec = if (rep.codecs?.contains(',') == true) rep.codecs.substringAfter(
                    ","
                ) else "unknown",
                url = manifest.baseUri, // The download URL is always the manifest URL
                manifestUrl = manifest.baseUri,
                httpHeaders = headers,
                height = rep.height,
                width = rep.width,
                bitrate = rep.bandwidth,
                fileSizeApproximate = approxBytes,
                duration = durationInMillis,
            )
        }

        if (formats.isEmpty()) {
            AppLogger.d("MPD Parse: No suitable video representations found.")
            return null
        }

        return VideoInfoWrapper(
            VideoInfo(
                title = "MPEG-DASH Stream",
                originalUrl = manifest.baseUri,
                formats = VideFormatEntityList(formats.sortedByDescending { it.height })
            ).apply {
                this.ext = "mp4"
                this.isRegularDownload = false
                this.duration = durationInMillis
                this.isLive = isLive
                isDetectedBySuperX = true
            })
    }

    /**
     * Parses an ISO 8601 duration string into milliseconds.
     * Robustly handles standard formats and non-standard variants (e.g., PT6333.74S, PT1M90S).
     */
    private fun parseIso8601Duration(duration: String?): Long {
        if (duration.isNullOrBlank()) return 0L

        // 1. Try strict standard parsing first
        try {
            return Duration.parse(duration).toMillis()
        } catch (e: Exception) {
            // If strict parsing fails, use regex for a more flexible extraction
        }

        // Pattern matches: optional Hours (H), optional Minutes (M), and optional Seconds (S)
        // Groups: 1=Hours, 2=Minutes, 3=Seconds
        val pattern =
            Regex("PT(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?")

        return try {
            val matchResult = pattern.matchEntire(duration)
            if (matchResult != null) {
                val hours = matchResult.groupValues[1].ifEmpty { "0" }.toDouble()
                val minutes = matchResult.groupValues[2].ifEmpty { "0" }.toDouble()
                val seconds = matchResult.groupValues[3].ifEmpty { "0" }.toDouble()

                val totalSeconds = (hours * 3600) + (minutes * 60) + seconds
                (totalSeconds * 1000).toLong()
            } else {
                AppLogger.e("Unrecognized duration format: $duration")
                0L
            }
        } catch (e: Exception) {
            AppLogger.e("Error calculating duration for: $duration - ${e.message}")
            0L
        }
    }


    /**
     * Approximate downloaded byte count for a stream of [bitrate] bits/sec
     * playing for [durationMs] milliseconds. Returns 0 if the inputs aren't
     * usable so the UI keeps showing "Unknown" in that case.
     */
    private fun approxFileSize(bitrate: Long, durationMs: Long): Long {
        if (bitrate <= 0 || durationMs <= 0) return 0L
        // bytes = bitrate (bits/s) * seconds / 8
        return bitrate * (durationMs / 1000.0).toLong() / 8
    }

    /**
     * Best-effort typical bitrate (bits/sec) for the given target height when
     * the manifest doesn't expose one. Tuned to match common streaming profiles
     * so the user sees a believable size estimate per quality.
     */
    private fun typicalBitrateForHeight(height: Int?): Long = when {
        height == null -> 0L
        height >= 2160 -> 18_000_000L
        height >= 1440 -> 9_000_000L
        height >= 1080 -> 4_500_000L
        height >= 720 -> 2_500_000L
        height >= 480 -> 1_200_000L
        height >= 360 -> 800_000L
        height >= 240 -> 500_000L
        height >= 144 -> 250_000L
        else -> 600_000L
    }

    /**
     * Best-effort height extraction from a media playlist URL. Recognised forms:
     *   - .../1080p.mp4.m3u8, .../1080p.av1.mp4.m3u8, .../720p/playlist.m3u8
     *   - .../1920x1080/index.m3u8, .../1280x720.mp4.m3u8
     *   - .../hls-480p-12345.m3u8
     */
    private fun inferHeightFromUrl(url: String): Int? {
        val cleaned = url.substringBefore('?').substringBefore('#').lowercase()

        // Match the LAST occurrence of <number>p in the path; take the one
        // closest to the filename to avoid latching on to a directory like
        // "multi=...:1080p:" earlier in the URL.
        val pRegex = Regex("(\\d{2,4})p[^/]*$")
        pRegex.find(cleaned)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

        // Fall back to width x height patterns.
        val xRegex = Regex("\\d{2,4}x(\\d{2,4})")
        xRegex.findAll(cleaned).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { return it }

        return null
    }

    /**
     * Some CDNs (notably doppiocdn-style hosts) advertise the full quality
     * ladder in the URL itself instead of in a master playlist. The pattern
     * looks like ".../multi=144p:,240p:,480p:,720p:,1080p:/.../480p.av1.mp4.m3u8"
     * and the site only ever serves one child playlist. Detect that pattern,
     * pull every advertised resolution out, and synthesise a selectable
     * [VideoFormatEntity] per resolution by swapping the height token in the
     * filename. Returns an empty list if the URL doesn't follow the pattern,
     * which signals the caller to fall back to single-quality behaviour.
     */
    private fun synthesizeMultiVariantFormats(
        manifest: HlsPlaylistParser.MediaPlaylist,
        durationMs: Long,
        headers: Map<String, String>,
    ): List<VideoFormatEntity> {
        val baseUri = manifest.baseUri
        val cleaned = baseUri.substringBefore('?').substringBefore('#')

        // Look for the multi=...: ladder block. It can appear with or without
        // a leading "multi=" depending on the site, but the colon-separated
        // resolution list (e.g. ":144p:,240p:,480p:,720p:") is the consistent
        // marker.
        val ladderRegex = Regex("multi=([^/]*?(?:\\d{2,4}p[^/]*?))/")
        val ladderMatch = ladderRegex.find(cleaned) ?: return emptyList()

        val resolutionRegex = Regex("(\\d{2,4})p")
        val advertisedHeights = resolutionRegex.findAll(ladderMatch.groupValues[1])
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .toSortedSet()

        if (advertisedHeights.size <= 1) return emptyList()

        // We need the height token in the filename so we know what to swap.
        // Without it we can't safely build sibling URLs.
        val currentHeight = inferHeightFromUrl(baseUri) ?: return emptyList()
        val currentToken = "${currentHeight}p"
        val filenameStart = cleaned.lastIndexOf('/').coerceAtLeast(0)
        val tokenIndex = cleaned.indexOf(currentToken, startIndex = filenameStart)
        if (tokenIndex < 0) return emptyList()

        // Preserve the query string and fragment from the original URL when
        // we rebuild siblings, since signed CDN URLs often carry tokens there.
        val querySuffix = baseUri.substring(cleaned.length)

        return advertisedHeights.map { height ->
            val swappedUrl = if (height == currentHeight) {
                baseUri
            } else {
                val swappedPath = cleaned.replaceRange(
                    tokenIndex,
                    tokenIndex + currentToken.length,
                    "${height}p"
                )
                swappedPath + querySuffix
            }

            val approxBytes = approxFileSize(
                bitrate = typicalBitrateForHeight(height),
                durationMs = durationMs
            )

            VideoFormatEntity(
                formatId = "hls-media-$height",
                format = "hls-${height}p",
                formatNote = "${height}p",
                ext = "mp4",
                vcodec = "unknown",
                acodec = "unknown",
                url = swappedUrl,
                manifestUrl = swappedUrl,
                httpHeaders = headers,
                height = height,
                width = 0,
                fileSizeApproximate = approxBytes,
                duration = durationMs
            )
        }
    }

    /**
     * Helper function to fetch the first available media playlist from a master playlist.
     * This is used to accurately determine if the stream is live.
     */
    private fun fetchFirstMediaPlaylist(
        manifest: HlsPlaylistParser.MasterPlaylist,
        headers: Map<String, String>
    ): HlsPlaylistParser.MediaPlaylist? {
        // Find the first variant that has a valid URL.
        val firstVariantUrl = manifest.variants.firstOrNull()?.url ?: return null

        return try {
            val request =
                Request.Builder().url(firstVariantUrl).headers(headers.toHeaders()).build()
            val response = client.getProxyOkHttpClient().newCall(request).execute()
            val content = response.body.string()

            if (!response.isSuccessful || content.isEmpty()) {
                return null
            }
            // Parse the content of the child playlist.
            val mediaPlaylist = HlsPlaylistParser.parse(content, firstVariantUrl)
            mediaPlaylist as? HlsPlaylistParser.MediaPlaylist
        } catch (e: Exception) {
            AppLogger.e("Failed to fetch child media playlist: $firstVariantUrl ${e.printStackTrace()}")
            null
        }
    }
}
