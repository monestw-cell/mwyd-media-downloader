package com.deniscerri.ytdl.work

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.afollestad.materialdialogs.utils.MDUtil.getStringArray
import com.deniscerri.ytdl.App
import com.deniscerri.ytdl.MainActivity
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.core.RuntimeManager
import com.deniscerri.ytdl.core.models.YTDLRequest
import com.deniscerri.ytdl.database.DBManager
import com.deniscerri.ytdl.database.models.HistoryItem
import com.deniscerri.ytdl.database.models.LogItem
import com.deniscerri.ytdl.database.repository.DownloadRepository
import com.deniscerri.ytdl.database.repository.LogRepository
import com.deniscerri.ytdl.database.repository.ResultRepository
import com.deniscerri.ytdl.util.Extensions.getIDFromYoutubeURL
import com.deniscerri.ytdl.util.Extensions.getMediaDuration
import com.deniscerri.ytdl.util.Extensions.toStringDuration
import com.deniscerri.ytdl.util.FileUtil
import com.deniscerri.ytdl.util.NotificationUtil
import com.deniscerri.ytdl.util.SubtitleAdjuster
import com.deniscerri.ytdl.util.WorkerEventBus
import com.deniscerri.ytdl.util.extractors.ytdlp.YTDLPUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit


class DownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val workNotif = NotificationUtil(App.instance).createDefaultWorkerNotification()

        return ForegroundInfo(
            1000000000,
            workNotif,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }



    @OptIn(ExperimentalStdlibApi::class)
    @SuppressLint("RestrictedApi")
    override suspend fun doWork(): Result {
        val workManager = WorkManager.getInstance(context)
        if (workManager.isRunning("download") || isStopped) return Result.Failure()

        setForegroundSafely()

        RuntimeManager.getInstance().init(context)
        runCatching { RuntimeManager.getInstance().assertInit(context) }

        val notificationUtil = NotificationUtil(App.instance)
        val dbManager = DBManager.getInstance(context)
        val dao = dbManager.downloadDao
        val historyDao = dbManager.historyDao
        val commandTemplateDao = dbManager.commandTemplateDao
        val logRepo = LogRepository(dbManager.logDao)
        val resultRepo = ResultRepository(dbManager.resultDao, commandTemplateDao, context)
        val ytdlpUtil = YTDLPUtil(context, commandTemplateDao)
        val handler = Handler(Looper.getMainLooper())
        val alarmScheduler = AlarmScheduler(context)
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

        // Reset any orphaned Active downloads from a previously killed app process
        val activeDownloadsAtStart = dao.getActiveDownloadsList()
        activeDownloadsAtStart.forEach {
            if (!RuntimeManager.idProcessMap.containsKey(it.id.toString())) {
                it.status = DownloadRepository.Status.Queued.toString()
                dao.update(it)
            } else {
                runningYTDLInstances.add(it.id)
            }
        }

        val time = System.currentTimeMillis() + 6000
        val priorityItemIDs = (inputData.getLongArray("priority_item_ids") ?: longArrayOf()).toMutableList()
        val continueAfterPriorityIds = inputData.getBoolean("continue_after_priority_ids", true)
        val queuedItems = if (priorityItemIDs.isEmpty()) {
            dao.getQueuedScheduledDownloadsUntil(time)
        }else {
            dao.getQueuedScheduledDownloadsUntilWithPriority(time, priorityItemIDs)
        }

        // this is needed for observe sources call, so it wont create result items
        // [removed]
        //val createResultItem = inputData.getBoolean("createResultItem", true)

        val confTmp = Configuration(context.resources.configuration)
        val locale = if (Build.VERSION.SDK_INT < 33) {
            sharedPreferences.getString("app_language", "")!!.ifEmpty { Locale.getDefault().language }
        }else{
            Locale.getDefault().language
        }.run {
            split("-")
        }.run {
            if (this.size == 1) Locale(this[0]) else Locale(this[0], this[1])
        }
        confTmp.setLocale(locale)
        val metrics = DisplayMetrics()
        val resources = Resources(context.assets, metrics, confTmp)

        val openQueueIntent = Intent(context, MainActivity::class.java)
        openQueueIntent.setAction(Intent.ACTION_VIEW)
        openQueueIntent.putExtra("destination", "Queue")
        val openDownloadQueue = PendingIntent.getActivity(
            context,
            1000000000,
            openQueueIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        queuedItems.collectLatest { items ->
            if (this@DownloadWorker.isStopped) return@collectLatest

            val activeDownloads = dao.getActiveDownloadsList()
            activeDownloads.forEach {
                if (RuntimeManager.idProcessMap.containsKey(it.id.toString())) {
                    runningYTDLInstances.add(it.id)
                }
            }

            val running = synchronized(runningYTDLInstances) { ArrayList(runningYTDLInstances) }
            val useScheduler = sharedPreferences.getBoolean("use_scheduler", false)
            if (items.isEmpty() && running.isEmpty()) {
                WorkManager.getInstance(context).cancelWorkById(this@DownloadWorker.id)
                return@collectLatest
            }

            if (useScheduler){
                if (items.none{it.downloadStartTime > 0L} && running.isEmpty() && !alarmScheduler.isDuringTheScheduledTime()) {
                    WorkManager.getInstance(context).cancelWorkById(this@DownloadWorker.id)
                    return@collectLatest
                }
            }

            if (priorityItemIDs.isEmpty() && !continueAfterPriorityIds && running.isEmpty()) {
                WorkManager.getInstance(context).cancelWorkById(this@DownloadWorker.id)
                return@collectLatest
            }

            val concurrentDownloads = sharedPreferences.getInt("concurrent_downloads", 1) - running.size
            val eligibleDownloads = if (priorityItemIDs.isNotEmpty()) {
                val tmp = priorityItemIDs.take(concurrentDownloads)
                items.filter { it.id !in running && tmp.contains(it.id) }
            }else{
                items.take(concurrentDownloads).filter {  it.id !in running }
            }

            eligibleDownloads.forEach{downloadItem ->
                runningYTDLInstances.add(downloadItem.id)
                val notification = notificationUtil.createDownloadServiceNotification(openDownloadQueue, downloadItem.title.ifEmpty { downloadItem.url })
                notificationUtil.notify(downloadItem.id.toInt(), notification)

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                    val logString = StringBuilder()
                    var logItem: LogItem? = null
                    val logDownloads = sharedPreferences.getBoolean("log_downloads", false) && !downloadItem.incognito

                    val writtenPath = downloadItem.format.format_note.contains("-P ")
                    val noCache = writtenPath || (!sharedPreferences.getBoolean("cache_downloads", true) && File(FileUtil.formatPath(downloadItem.downloadPath)).canWrite())
                    val downloadLocation = downloadItem.downloadPath
                    val keepCache = sharedPreferences.getBoolean("keep_cache", false)
                    val cacheDir = FileUtil.getCacheDownloadsPath(context)
                    val tempFileDir = File(cacheDir, downloadItem.id.toString())
                    var request: YTDLRequest? = null
                    var commandString = ""
                    var initialLogDetails = ""

                    runCatching {
                        RuntimeManager.getInstance().assertInit(context)
                        val req = ytdlpUtil.buildYTDLRequest(downloadItem)
                        request = req

                        // DISABLED BECAUSE YT_DLP CONSIDERS DOWNLOAD FAILURE IF -U PART FAILS, #1043
//                    val updateYTDLP = sharedPreferences.getBoolean("update_ytdlp_while_downloading", false)
//                    if (updateYTDLP) {
//                        request.addOption("-U")
//                    }

                        downloadItem.status = DownloadRepository.Status.Active.toString()

                        tempFileDir.mkdirs()
                        notificationUtil.cancelDownloadNotification(NotificationUtil.DOWNLOAD_RESUME_NOTIFICATION_ID + downloadItem.id.toInt())

                        commandString = ytdlpUtil.parseYTDLRequestString(req)
                        initialLogDetails = "Downloading:\n" +
                                "Title: ${downloadItem.title}\n" +
                                "URL: ${downloadItem.url}\n" +
                                "Type: ${downloadItem.type}\n" +
                                "Command:\n$commandString \n\n"
                        logString.append(initialLogDetails)
                        val createdLog = LogItem(
                            0,
                            downloadItem.title.ifBlank { downloadItem.playlistTitle.ifEmpty { downloadItem.url } },
                            logString.toString(),
                            downloadItem.format,
                            downloadItem.type,
                            System.currentTimeMillis(),
                        )

                        if (logDownloads) createdLog.id = logRepo.insert(createdLog)
                        logItem = createdLog
                        downloadItem.logID = createdLog.id
                        dao.update(downloadItem)

                        RuntimeManager.getInstance().destroyProcessById(downloadItem.id.toString())
                        RuntimeManager.getInstance().execute(
                            request = request!!,
                            processId = downloadItem.id.toString(),
                            redirectErrorStream = true,
                            usingCacheDir = true
                        ){ progress, _, line ->
                            WorkerEventBus.post(WorkerProgress(progress.toInt(), line, downloadItem.id, downloadItem.logID))
                            val title: String = downloadItem.title.ifEmpty { downloadItem.url }
                            notificationUtil.updateDownloadNotification(
                                downloadItem.id.toInt(),
                                line, progress.toInt(), 0, title,
                                NotificationUtil.DOWNLOAD_SERVICE_CHANNEL_ID
                            )
                            CoroutineScope(Dispatchers.IO).launch {
                                if (logDownloads && logItem != null) {
                                    logRepo.update(line, logItem!!.id)
                                }
                                logString.append("$line\n")
                            }
                        }
                    }.onSuccess {
                        resultRepo.updateDownloadItem(downloadItem)?.apply {
                            dao.updateWithoutUpsert(this)
                        }
                        //val wasQuickDownloaded = resultDao.getCountInt() == 0
                        runBlocking {
                            var finalPaths = mutableListOf<String>()

                            if (noCache){
                                WorkerEventBus.post(WorkerProgress(100, "Scanning Files", downloadItem.id, downloadItem.logID))
                                val outputSequence = it.out.split("\n")
                                finalPaths =
                                    outputSequence.asSequence()
                                        .filter { it.startsWith("'/storage") }
                                        .map { it.removeSuffix("\n") }
                                        .map { it.removeSurrounding("'", "'") }
                                        .toMutableList()

                                finalPaths.addAll(
                                    outputSequence.asSequence()
                                        .filter { it.startsWith("[SplitChapters]") && it.contains("Destination: ") }
                                        .map { it.split("Destination: ")[1] }
                                        .map { it.removeSuffix("\n") }
                                        .toList()
                                )

                                finalPaths.sortBy { File(it).lastModified() }
                                finalPaths = finalPaths.distinct().toMutableList()
                                FileUtil.scanMedia(finalPaths, context)
                            }else{
                                if (tempFileDir.exists()) {
                                    val trimRange = if (downloadItem.downloadSections.isNotBlank()) {
                                        SubtitleAdjuster.parseTrimRange(downloadItem.downloadSections)
                                    } else null

                                    if (downloadItem.downloadSections.isNotBlank()) {
                                        val mediaExts = listOf("mp4", "mkv", "webm", "m4a", "mp3", "opus", "flac", "aac", "ogg")
                                        tempFileDir.listFiles()?.filter { f -> f.isFile && mediaExts.contains(f.extension.lowercase()) && !f.name.endsWith(".part") && !f.name.startsWith("fixed_") && !f.name.startsWith("embedded_") }?.forEach { mediaFile ->
                                            sanitizeTrimmedMediaFile(mediaFile)
                                        }
                                    }

                                    val rawSubLang = downloadItem.videoPreferences.subsLanguages.trim()
                                    val singleSubLang = rawSubLang.split(",").map { it.trim().removeSuffix(".*") }.filter { it.isNotBlank() }.let { if (it.size == 1) it[0] else null }
                                    if (singleSubLang != null && singleSubLang != "all") {
                                        filterSingleSubtitleFile(tempFileDir, singleSubLang)
                                    }

                                    if (trimRange != null) {
                                        val subExts = listOf("vtt", "srt")
                                        tempFileDir.listFiles()?.filter { f -> f.isFile && subExts.contains(f.extension.lowercase()) }?.forEach { subFile ->
                                            SubtitleAdjuster.adjustSubtitleFile(subFile, trimRange.first, trimRange.second)
                                        }

                                        if (downloadItem.videoPreferences.embedSubs) {
                                            embedSubtitlesIntoMedia(tempFileDir)
                                        }
                                    }
                                }

                                //move file from internal to set download directory
                                WorkerEventBus.post(WorkerProgress(100, "Moving file to ${FileUtil.formatPath(downloadLocation)}", downloadItem.id, downloadItem.logID))
                                try {
                                    finalPaths = withContext(Dispatchers.IO){
                                        FileUtil.moveFile(tempFileDir.absoluteFile,context, downloadLocation, keepCache){ p ->
                                            WorkerEventBus.post(WorkerProgress(p, "Moving file to ${FileUtil.formatPath(downloadLocation)}", downloadItem.id, downloadItem.logID))
                                        }
                                    }.filter { !it.matches("\\.(description)|(txt)\$".toRegex()) }.toMutableList()

                                    if (finalPaths.isNotEmpty()){
                                        WorkerEventBus.post(WorkerProgress(100, "Moved file to ${FileUtil.formatPath(downloadLocation)}", downloadItem.id, downloadItem.logID))
                                    }
                                }catch (e: Exception){
                                    e.printStackTrace()
                                    if (e.message?.isNotBlank() == true) {
                                        handler.postDelayed({
                                            Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                                        }, 1000)
                                    }

                                }
                            }


                            val nonMediaExtensions = mutableListOf<String>().apply {
                                addAll(context.getStringArray(R.array.thumbnail_containers_values))
                                addAll(context.getStringArray(R.array.sub_formats_values).filter { it.isNotBlank() })
                                add("description")
                                add("txt")
                            }
                            finalPaths = finalPaths.filter { path -> !nonMediaExtensions.any { path.endsWith(it) } }.toMutableList()
                            request?.let { FileUtil.deleteConfigFiles(it) }
                            if (!keepCache && !noCache) {
                                tempFileDir.deleteRecursively()
                            }

                            //put download in history
                            if (!downloadItem.incognito) {
                                if (request?.hasOption("--download-archive") == true && finalPaths.isEmpty()) {
                                    handler.postDelayed({
                                        Toast.makeText(context, resources.getString(R.string.download_already_exists), Toast.LENGTH_LONG).show()
                                    }, 100)
                                }else{
                                    if (finalPaths.isNotEmpty()) {
                                        val unixTime = System.currentTimeMillis() / 1000
                                        finalPaths.first().apply {
                                            val file = File(this)
                                            var duration = downloadItem.duration
                                            val d = file.getMediaDuration(context)
                                            if (d > 0) duration = d.toStringDuration(Locale.US)

                                            downloadItem.format.filesize = file.length()
                                            downloadItem.format.container = file.extension
                                            downloadItem.duration = duration
                                        }

                                        val historyItem = HistoryItem(0,
                                            downloadItem.url,
                                            downloadItem.title.ifEmpty { downloadItem.playlistTitle },
                                            downloadItem.author,
                                            downloadItem.duration,
                                            downloadItem.thumb,
                                            downloadItem.type,
                                            unixTime,
                                            finalPaths,
                                            downloadItem.website,
                                            downloadItem.format,
                                            downloadItem.format.filesize,
                                            downloadItem.id,
                                            commandString)
                                        historyDao.insert(historyItem)
                                    }
                                }
                            }

                            withContext(Dispatchers.Main) {
                                notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())
                                notificationUtil.createDownloadFinished(
                                    downloadItem.id, downloadItem.title, downloadItem.type,  if (finalPaths.isEmpty()) null else finalPaths, resources
                                )
                            }

//                            if (wasQuickDownloaded && createResultItem){
//                                runCatching {
//                                    eventBus.post(WorkerProgress(100, "Creating Result Items", downloadItem.id))
//                                    runBlocking {
//                                        infoUtil.getFromYTDL(downloadItem.url).forEach { res ->
//                                            if (res != null) {
//                                                resultDao.insert(res)
//                                            }
//                                        }
//                                    }
//                                }
//                            }

                            dao.delete(downloadItem.id)

                            if (logDownloads && logItem != null){
                                logRepo.update(initialLogDetails + it.out, logItem!!.id, true)
                            }
                        }

                    }.onFailure {
                        request?.let { FileUtil.deleteConfigFiles(it) }
                        withContext(Dispatchers.Main){
                            notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())
                        }

                        val currentStatus = runCatching { dao.checkStatus(downloadItem.id) }.getOrNull()
                        val isPaused = it is RuntimeManager.CanceledException || currentStatus == DownloadRepository.Status.Paused
                        val isCancelled = currentStatus == DownloadRepository.Status.Cancelled || this@DownloadWorker.isStopped

                        if (isPaused) {
                            // Preserving partial files (.part) for resume; keeping status as Paused without error
                            downloadItem.status = DownloadRepository.Status.Paused.toString()
                            runBlocking {
                                dao.update(downloadItem)
                            }
                            return@onFailure
                        }

                        if (isCancelled) {
                            tempFileDir.deleteRecursively()
                            return@onFailure
                        }

                        if (it.message?.contains("JSONDecodeError") == true ||
                            it.message?.contains("Requested format is not available") == true ||
                            it.message?.contains("HTTP Error 403") == true ||
                            it.message?.contains("Forbidden") == true) {
                            val cachePath = FileUtil.getInfoJsonPath(context)
                            val id = downloadItem.url.getIDFromYoutubeURL() ?: downloadItem.url
                            val infoJsonName = ytdlpUtil.hash(id)
                            File(cachePath).walkBottomUp().filter { f -> f.name.startsWith(infoJsonName) }.forEach { f -> f.delete() }
                        }

                        val activeLog = logItem ?: LogItem(
                            0,
                            downloadItem.title.ifBlank { downloadItem.playlistTitle.ifEmpty { downloadItem.url } },
                            logString.toString(),
                            downloadItem.format,
                            downloadItem.type,
                            System.currentTimeMillis(),
                        )

                        if (logDownloads && activeLog.id > 0L){
                            logRepo.update(it.message ?: "", activeLog.id)
                        }else if (logDownloads){
                            logString.append("${it.message ?: it.stackTraceToString()}\n")
                            activeLog.content = logString.toString()
                            val logID = logRepo.insert(activeLog)
                            downloadItem.logID = logID
                        }

                        // Preserving tempFileDir with .part files so user can resume/retry without losing progress

                        Log.e(TAG, context.getString(R.string.failed_download), it)
                        Log.e(TAG, "Download failed. Command: $commandString\nError output: ${it.message}")
                        notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())

                        downloadItem.status = DownloadRepository.Status.Error.toString()
                        runBlocking {
                            dao.update(downloadItem)
                        }

                        notificationUtil.createDownloadErrored(
                            downloadItem.id,
                            downloadItem.title.ifEmpty { downloadItem.url },
                            it.message,
                            downloadItem.logID,
                            resources
                        )

                        WorkerEventBus.post(WorkerProgress(100, it.toString(), downloadItem.id, downloadItem.logID))
                    }
                } finally {
                    runningYTDLInstances.remove(downloadItem.id)
                }
            }
        }

            if (eligibleDownloads.isNotEmpty()){
                eligibleDownloads.forEach {
                    it.status = DownloadRepository.Status.Active.toString()
                    priorityItemIDs.remove(it.id)
                }
                dao.updateMultiple(eligibleDownloads)
            }
        }

        return Result.success()
    }

    private fun sanitizeTrimmedMediaFile(file: File) {
        if (!RuntimeManager.isFfmpegAvailable || !file.exists() || file.length() == 0L) return
        val fixedFile = File(file.parentFile, "fixed_${file.name}")
        val args = listOf(
            "-y",
            "-i", file.absolutePath,
            "-c", "copy",
            "-map", "0",
            "-map_chapters", "-1",
            "-avoid_negative_ts", "make_zero",
            fixedFile.absolutePath
        )
        val success = RuntimeManager.executeFfmpeg(args, 30)
        if (success && fixedFile.exists() && fixedFile.length() > 0) {
            file.delete()
            fixedFile.renameTo(file)
        } else if (fixedFile.exists()) {
            fixedFile.delete()
        }
    }

    private fun embedSubtitlesIntoMedia(tempFileDir: File) {
        if (!RuntimeManager.isFfmpegAvailable) return
        val mediaExts = listOf("mp4", "mkv", "webm", "m4a", "mov")
        val mediaFile = tempFileDir.listFiles()?.firstOrNull { f ->
            f.isFile && mediaExts.contains(f.extension.lowercase()) && !f.name.endsWith(".part") && !f.name.startsWith("fixed_") && !f.name.startsWith("embedded_")
        } ?: return

        val subExts = listOf("vtt", "srt")
        val subFile = tempFileDir.listFiles()?.firstOrNull { f ->
            f.isFile && subExts.contains(f.extension.lowercase()) && !f.name.startsWith("fixed_") && !f.name.startsWith("embedded_")
        } ?: return

        val tempOutput = File(tempFileDir, "embedded_${mediaFile.name}")
        val mediaExt = mediaFile.extension.lowercase()
        val subCodec = when (mediaExt) {
            "mp4", "m4v", "mov" -> "mov_text"
            "webm" -> "webvtt"
            else -> "copy"
        }
        val langCode = subFile.name.substringBeforeLast('.').substringAfterLast('.').ifBlank { "und" }

        val args = listOf(
            "-y",
            "-i", mediaFile.absolutePath,
            "-i", subFile.absolutePath,
            "-c", "copy",
            "-c:s", subCodec,
            "-map", "0:v?",
            "-map", "0:a?",
            "-map", "1:0",
            "-metadata:s:s:0", "language=$langCode",
            tempOutput.absolutePath
        )

        val success = RuntimeManager.executeFfmpeg(args, 60)
        if (success && tempOutput.exists() && tempOutput.length() > 0) {
            mediaFile.delete()
            tempOutput.renameTo(mediaFile)
            subFile.delete()
        } else if (tempOutput.exists()) {
            tempOutput.delete()
        }
    }

    private fun filterSingleSubtitleFile(directory: File, singleSubLang: String) {
        val subExts = listOf("vtt", "srt", "ass", "sub", "lrc")
        val subFiles = directory.listFiles()?.filter { f -> f.isFile && subExts.contains(f.extension.lowercase()) } ?: emptyList()
        if (subFiles.size > 1) {
            val exactMatch = subFiles.firstOrNull { it.name.contains(".$singleSubLang.", ignoreCase = true) }
            val prefixMatch = subFiles.firstOrNull { it.name.contains(".$singleSubLang-", ignoreCase = true) || it.name.contains("_${singleSubLang}_", ignoreCase = true) }
            val chosenSub = exactMatch ?: prefixMatch ?: subFiles.first()
            subFiles.forEach { f ->
                if (f != chosenSub) {
                    f.delete()
                }
            }
        }
    }

    companion object {
        val runningYTDLInstances: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())
        const val TAG = "DownloadWorker"
    }

    class WorkerProgress(
        val progress: Int,
        val output: String,
        val downloadItemID: Long,
        val logItemID: Long?
    )

}