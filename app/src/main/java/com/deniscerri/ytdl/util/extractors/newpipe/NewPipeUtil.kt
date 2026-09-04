package com.deniscerri.ytdl.util.extractors.newpipe

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.database.models.ChapterItem
import com.deniscerri.ytdl.database.models.Format
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.viewmodel.ResultViewModel
import com.deniscerri.ytdl.util.Extensions.getIDFromYoutubeURL
import com.deniscerri.ytdl.util.Extensions.toStringDuration
import com.deniscerri.ytdl.util.extractors.newpipe.potoken.NewPipePoTokenGenerator
import com.google.gson.Gson
import okhttp3.OkHttpClient
import org.json.JSONException
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

class NewPipeUtil(context: Context) {
    private var sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val countryCode = sharedPreferences.getString("locale", "")!!.ifEmpty { "US" }
    private val language = sharedPreferences.getString("app_language", "")!!.ifEmpty { "en" }
    private val useAppLanguageForMetadata = sharedPreferences.getBoolean("use_app_language_for_metadata", false)

    companion object {
        private var isInitialized = false
    }

    init {
        if (!isInitialized) {
            try {
                if (useAppLanguageForMetadata) {
                    NewPipe.init(NewPipeDownloaderImpl.instance, Localization(language, countryCode))
                } else {
                    NewPipe.init(NewPipeDownloaderImpl.instance)
                }
                isInitialized = true
            } catch (_: Exception) {}
        }
    }

    fun fetchFastOEmbedVideo(url: String): ResultItem? {
        val ytId = url.getIDFromYoutubeURL() ?: return null
        return try {
            val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$ytId&format=json"
            val request = okhttp3.Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val response = NewPipeDownloaderImpl.sharedClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = org.json.JSONObject(body)
            val title = json.optString("title", "")
            val author = json.optString("author_name", "").removeSuffix(" - Topic")
            val thumb = "https://i.ytimg.com/vi/$ytId/hqdefault.jpg"

            ResultItem(
                id = 0,
                url = url,
                title = title,
                author = author,
                duration = "00:00",
                thumb = thumb,
                website = "youtube",
                playlistTitle = "",
                formats = arrayListOf(),
                urls = "",
                chapters = arrayListOf()
            )
        } catch (e: Exception) {
            null
        }
    }

    fun getVideoData(url : String) : Result<List<ResultItem>> {
        val ytId = url.getIDFromYoutubeURL()
        val cleanUrl = if (ytId != null) "https://www.youtube.com/watch?v=$ytId" else url

        // 1. Try NewPipe StreamInfo first
        try {
            val streamInfo = StreamInfo.getInfo(cleanUrl)
            val vid = createVideoFromStream(streamInfo, url, true)
            if (vid != null && vid.title.isNotBlank()) {
                return Result.success(listOf(vid))
            }
        } catch (e: Exception) {
            Log.e("NewPipeUtil", "StreamInfo.getInfo failed for $url: ${e.message}")
        }

        // 2. Ultra-fast oEmbed fallback in < 200ms
        val fastItem = fetchFastOEmbedVideo(url)
        if (fastItem != null && fastItem.title.isNotBlank()) {
            return Result.success(listOf(fastItem))
        }

        return Result.failure(Throwable("Failed to parse stream"))
    }

    fun getFormats(url: String) : Result<List<Format> > {
        try {
            val ytId = url.getIDFromYoutubeURL()
            val cleanUrl = if (ytId != null) "https://www.youtube.com/watch?v=$ytId" else url
            val streamInfo = StreamInfo.getInfo(cleanUrl)
            val vid = createVideoFromStream(streamInfo, url, true)
            return if (vid != null) Result.success(vid.formats) else Result.failure(Throwable("Failed to parse formats"))
        }catch(e: Exception) {
            Log.e("NewPipeUtil", "getFormats failed for $url: ${e.message}", e)
            if (e is CancellationException) throw e
            return Result.failure(e)
        }
    }

    fun getFormatsForAll(urls: List<String>, progress: (progress: ResultViewModel.MultipleFormatProgress) -> Unit) : Result<MutableList<MutableList<Format>>> {
        return kotlin.runCatching {
            val formatCollection = mutableListOf<MutableList<Format>>()
            urls.forEach { url ->
                val streamInfo = StreamInfo.getInfo(url)
                createVideoFromStream(streamInfo, url, true).apply {
                    if (this!!.formats.isEmpty()) return Result.failure(Throwable())
                    formatCollection.add(this.formats.toMutableList())
                    progress(ResultViewModel.MultipleFormatProgress(url, this.formats))
                }
            }
            return Result.success(formatCollection)
        }.onFailure {
            return Result.failure(it)
        }
    }

    @Throws(JSONException::class)
    fun search(query: String): Result<ArrayList<ResultItem>> {
        try {
            val items = arrayListOf<ResultItem>()
            val res = SearchInfo.getInfo(NewPipe.getService(ServiceList.YouTube.serviceId),
                NewPipe.getService(ServiceList.YouTube.serviceId)
                    .searchQHFactory
                    .fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), ""))

            if (res.relatedItems.isEmpty()) return Result.failure(Throwable())

            for (i in 0 until res.relatedItems.size) {
                val element = res.relatedItems[i]
                if (element is StreamInfoItem) {
                    if (element.duration <= 0) continue
                    val v = createVideoFromStreamInfoItem(element, element.url) ?: continue
                    items.add(v)
                }
            }
            return Result.success(items)

        }catch (e: Exception){
            return Result.failure(e)
        }
    }

    @Throws(JSONException::class)
    fun searchMusic(query: String): Result<ArrayList<ResultItem>> {
        try {
            val items = arrayListOf<ResultItem>()
            val res = SearchInfo.getInfo(NewPipe.getService(ServiceList.YouTube.serviceId),
                NewPipe.getService(ServiceList.YouTube.serviceId)
                    .searchQHFactory
                    .fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), ""))
            if (res.relatedItems.isEmpty()) return Result.failure(Throwable())

            for (i in 0 until res.relatedItems.size) {
                val element = res.relatedItems[i]
                if (element is StreamInfoItem) {
                    if (element.duration <= 0) continue
                    val v = createVideoFromStreamInfoItem(element, element.url) ?: continue
                    items.add(v)
                }
            }
            return Result.success(items)

        }catch (e: Exception){
            return Result.failure(e)
        }
    }

    fun getStreamingUrlAndChapters(url: String) : Result<Pair<List<String>, List<ChapterItem>?>> {
        try {
            val streamInfo = StreamInfo.getInfo(url)
            val item = createVideoFromStream(streamInfo, url)!!

            val videoURL = item.formats.filter { it.vcodec.isNotBlank() && it.vcodec != "none" }.firstOrNull { !it.url.isNullOrBlank() }?.url ?: ""
            val audioURL = item.formats.filter { it.vcodec.isBlank() || it.vcodec == "none" }.firstOrNull { !it.url.isNullOrBlank() }?.url ?: ""

            val urls = listOf(videoURL, audioURL)
            val chapters = item.chapters
            return Result.success(Pair(urls, chapters))
        }catch (e: Exception) {
            return Result.failure(e)
        }
    }

    suspend fun getChannelData(url: String, progress: suspend (pagedResults: MutableList<ResultItem>) -> Unit) : Result<List<ResultItem>> {
        try {
            //return Result.failure(Throwable())
            val req = ChannelInfo.getInfo(ServiceList.YouTube, url)
            println(Gson().toJson(req))
            val items = mutableListOf<ResultItem>()
            for (tab in req.tabs) {
                if (listOf("videos", "shorts", "livestreams").contains(tab.contentFilters[0])) {
                    val tabInfo = ChannelTabInfo.getInfo(ServiceList.YouTube, tab)
                    val tmp = getChannelTabData(tab, tabInfo, req.name, "${url}/${tabInfo.url.split("/").last()}") {
                        progress(it)
                    }
                    if (tmp.isFailure) continue
                    else items.addAll(tmp.getOrNull()!!)
                }
            }
            return Result.success(items)
        }catch (e: Exception) {
            return Result.failure(e)
        }
    }

    private suspend fun getChannelTabData(linkHandler: ListLinkHandler, tabInfo: ChannelTabInfo, channelName: String, playlistURL: String, progress: suspend (pagedResults: MutableList<ResultItem>) -> Unit) : Result<List<ResultItem>> {
        try {
            val totalItems = mutableListOf<ResultItem>()
            var nextPage : Page? = null
            var playlistName = ""

            while (true) {
                val items = mutableListOf<ResultItem>()
                val req = if (nextPage == null) {
                    if (tabInfo.hasNextPage()) {
                        nextPage = tabInfo.nextPage
                    }
                    playlistName = "$channelName - ${tabInfo.name}"
                    tabInfo.relatedItems.toList()
                } else {
                    val tmp = ChannelTabInfo.getMoreItems(ServiceList.YouTube, linkHandler, nextPage)
                    nextPage = if (tmp.hasNextPage()) tmp.nextPage else null
                    tmp.items.toList()
                }

                if (req.isEmpty()) return Result.failure(Throwable())

                for (element in req) {
                    if (element is StreamInfoItem) {
                        if (element.duration <= 0) continue
                        val v = createVideoFromStreamInfoItem(element, element.url) ?: continue
                        v.apply {
                            playlistTitle = playlistName
                            this.playlistURL = playlistURL
                            items.add(this)
                        }
                    }
                }

                totalItems.addAll(items)
                progress(items)
                if (nextPage == null || items.isEmpty()) break
            }

            return Result.success(totalItems)
        }catch (e: Exception) {
            return Result.failure(e)
        }
    }

    suspend fun getPlaylistData(playlistURL: String, progress: suspend (pagedResults: MutableList<ResultItem>) -> Unit) : Result<List<ResultItem>> {
        try {
            val totalItems = mutableListOf<ResultItem>()
            var nextPage : Page? = null
            var playlistName = ""

            while (true) {
                val items = mutableListOf<ResultItem>()
                val req = if (nextPage == null) {
                    val tmp = PlaylistInfo.getInfo(ServiceList.YouTube, playlistURL)
                    if (tmp.hasNextPage()) {
                        nextPage = tmp.nextPage
                    }
                    playlistName = tmp.name
                    tmp.relatedItems.toList()
                } else {
                    val tmp = PlaylistInfo.getMoreItems(ServiceList.YouTube, playlistURL, nextPage)
                    nextPage = if (tmp.hasNextPage()) tmp.nextPage else null
                    tmp.items.toList()
                }

                for (element in req) {
                    if (element is StreamInfoItem) {
                        if (element.duration <= 0) continue
                        val v = createVideoFromStreamInfoItem(element, element.url) ?: continue
                        v.apply {
                            playlistTitle = playlistName
                            this.playlistURL = playlistURL
                            items.add(this)
                        }
                    }
                }

                totalItems.addAll(items)
                progress(items)
                if (nextPage == null || items.isEmpty()) break
            }

            return Result.success(totalItems)
        }catch (e: Exception) {
            return Result.failure(e)
        }
    }


    fun getTrending(): ArrayList<ResultItem> {
        try {
            val items = arrayListOf<ResultItem>()
            val kioskList = NewPipe.getService(ServiceList.YouTube.serviceId).kioskList
            kioskList.forceContentCountry(ContentCountry(countryCode))

            val extractor = kioskList.getExtractorById("trending_music", null)
            extractor.fetchPage()

            val info = KioskInfo.getInfo(extractor)
            if (info.relatedItems.isEmpty()) return arrayListOf()

            for (i in 0 until info.relatedItems.size) {
                val element = info.relatedItems[i]
                if (element is StreamInfoItem) {
                    if (element.duration <= 0) continue
                    val v = createVideoFromStreamInfoItem(element, element.url) ?: continue
                    items.add(v)
                }
            }

            return items
        }catch (err: Exception) {
            return arrayListOf()
        }
    }

    private fun createVideoFromStreamInfoItem(stream: StreamInfoItem, url: String) : ResultItem? {
        var video: ResultItem? = null
        try {
            val id = url.getIDFromYoutubeURL()
            val title = stream.name
            val author = stream.uploaderName.removeSuffix(" - Topic")
            val duration = stream.duration.toInt().toStringDuration(Locale.US)
            val thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

            video = ResultItem(0,
                url,
                title,
                author,
                duration,
                thumb,
                "youtube",
                "",
                ArrayList(),
                "",
                ArrayList()
            )

        } catch (e: Exception) {
            Log.e("NewPipeUtil", e.toString())
        }
        return video
    }

    private fun createVideoFromStream(stream: StreamInfo, url: String, ignoreFormatPreference : Boolean = false): ResultItem? {
        var video: ResultItem? = null
        try {
            val id = url.getIDFromYoutubeURL() ?: stream.id ?: ""
            val title = stream.name ?: ""
            val author = (stream.uploaderName ?: stream.subChannelName ?: "").removeSuffix(" - Topic")
            val duration = (stream.duration ?: 0L).toInt().toStringDuration(Locale.US)
            val thumb = if (id.isNotEmpty()) "https://i.ytimg.com/vi/$id/hqdefault.jpg" else (stream.thumbnails?.firstOrNull()?.url ?: "")
            val formats : ArrayList<Format> = ArrayList()


            if(sharedPreferences.getString("formats_source", "newpipe") != "yt-dlp" || ignoreFormatPreference){
                val durationSec = stream.duration
                if (stream.audioStreams.isNotEmpty()){
                    stream.audioStreams = stream.audioStreams.sortedByDescending { it.bitrate }
                    for (f in 0 until stream.audioStreams.size){
                        val it = stream.audioStreams[f]
                        if (it.bitrate == 0 || listOf(599, 600).contains(it.itag)) continue

                        var fileSize = it.itagItem?.contentLength ?: 0L
                        if (fileSize <= 0L && it.bitrate > 0 && durationSec > 0) {
                            fileSize = (it.bitrate.toLong() * durationSec) / 8L
                        }

                        val containerName = it.format?.name ?: "m4a"
                        val sampleRateStr = (it.itagItem?.sampleRate ?: 44100).toString()
                        val resNote = it.itagItem?.getResolutionString() ?: "${it.bitrate / 1000}kbps"

                        val formatObj = Format(
                            format_id = it.itag.toString(),
                            container = containerName,
                            acodec = it.codec ?: "m4a",
                            filesize = fileSize,
                            format_note = (it.audioTrackName ?: resNote) + " Audio",
                            lang = it.audioLocale?.language,
                            asr = sampleRateStr,
                            url = it.content ?: "",
                            tbr = (it.bitrate / 1000).toString() + "k",
                        )

                        formats.add(formatObj)
                    }

                    val hasDefaultFormat = formats.firstOrNull { it.format_note.contains("ORIGINAL", true) }
                    if (hasDefaultFormat != null) {
                        formats.remove(hasDefaultFormat)
                        formats.add(0, hasDefaultFormat)
                    }
                }

                if (stream.videoOnlyStreams.isNotEmpty()){
                    for (f in 0 until stream.videoOnlyStreams.size){
                        val it = stream.videoOnlyStreams[f]
                        if (it.bitrate == 0) continue

                        var fileSize = it.itagItem?.contentLength ?: 0L
                        if (fileSize <= 0L && it.bitrate > 0 && durationSec > 0) {
                            fileSize = (it.bitrate.toLong() * durationSec) / 8L
                        }

                        val containerName = it.format?.name ?: "mp4"
                        val resNote = it.itagItem?.getResolutionString() ?: it.quality ?: "${it.height}p"

                        val formatObj = Format(
                            format_id = it.itag.toString(),
                            container = containerName,
                            vcodec = it.codec ?: "mp4",
                            format_note = resNote,
                            filesize = fileSize,
                            url = it.content ?: "",
                            tbr = (it.bitrate / 1000).toString() + "k",
                            _width = it.width.toString(),
                            _height = it.height.toString()
                        )
                        formats.add(formatObj)
                    }
                }

                if (stream.videoStreams.isNotEmpty()){
                    for (f in 0 until stream.videoStreams.size){
                        val it = stream.videoStreams[f]
                        if (it.bitrate == 0) continue

                        var fileSize = it.itagItem?.contentLength ?: 0L
                        if (fileSize <= 0L && it.bitrate > 0 && durationSec > 0) {
                            fileSize = (it.bitrate.toLong() * durationSec) / 8L
                        }

                        val containerName = it.format?.name ?: "mp4"
                        val resNote = it.itagItem?.getResolutionString() ?: it.quality ?: "${it.height}p"

                        val formatObj = Format(
                            format_id = it.itag.toString(),
                            container = containerName,
                            vcodec = it.codec ?: "mp4",
                            format_note = resNote,
                            filesize = fileSize,
                            url = it.content ?: "",
                            tbr = (it.bitrate / 1000).toString() + "k",
                            _width = it.width.toString(),
                            _height = it.height.toString()
                        )
                        formats.add(formatObj)
                    }
                }


                formats.groupBy { it.format_id }.forEach {
                    if (it.value.count() > 1) {
                        it.value.filter { f-> !f.format_note.contains("original", true) }.forEachIndexed { index, format -> format.format_id = format.format_id.split("-")[0] + "-${index}" }
                        val defaultLang = it.value.find { f -> f.format_note.contains("original", true) }
                        defaultLang?.format_id = (defaultLang?.format_id?.split("-")?.get(0) ?: "") + "-${it.value.size-1}"
                    }
                }
            }

            val chapters = ArrayList<ChapterItem>()
            if (stream.streamSegments.isNotEmpty()){
                for (c in 0 until stream.streamSegments.size){
                    val chapter = stream.streamSegments[c]
                    val end = if (c == stream.streamSegments.size - 1) stream.duration.toInt() else stream.streamSegments[c+1].startTimeSeconds
                    val item = ChapterItem(chapter.startTimeSeconds.toLong(), end.toLong(), chapter.title)
                    chapters.add(item)
                }
            }

            video = ResultItem(0,
                url,
                title,
                author,
                duration,
                thumb,
                "youtube",
                "",
                formats,
                if (stream.hlsUrl.isNotBlank() && stream.hlsUrl != "null") stream.hlsUrl else "",
                chapters
            )
        } catch (e: Exception) {
            Log.e("NewPipeUtil", e.toString())
        }
        return video
    }
}