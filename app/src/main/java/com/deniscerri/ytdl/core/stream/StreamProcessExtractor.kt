package com.deniscerri.ytdl.core.stream

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import kotlin.math.max

internal class StreamProcessExtractor(
    private val buffer: StringBuffer,
    private val stream: InputStream,
    private val callback: ((Float, Long, String) -> Unit)?
) : Thread() {
    private val pDownload = Pattern.compile("\\[download\\]\\s+(\\d+(?:\\.\\d+)?)%")
    private val pEta = Pattern.compile("ETA\\s+(?:(\\d+):)?(\\d+):(\\d+)")
    private val pAria2c = Pattern.compile("\\[#\\w{6}.*\\((\\d*\\.*\\d+)%\\).*?((\\d+)m)*((\\d+)s)*]")
    private val pTimeRanges = Pattern.compile("(?:time ranges:|time range:)\\s*([\\d\\.]+)-([\\d\\.]+)")
    private val pDuration = Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)")
    private val pFFmpegTime = Pattern.compile("(?:size|frame)=.*time=\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)")
    private val pFFmpegSpeed = Pattern.compile("speed=\\s*([\\d\\.]+)x")

    private var progress = PERCENT
    private var eta = ETA
    private var expectedDuration = 0f
    private var lastFFmpegTime = 0f

    init {
        start()
    }

    override fun run() {
        try {
            val input: Reader = InputStreamReader(stream, StandardCharsets.UTF_8)
            val currentLine = StringBuilder()
            var nextChar: Int
            while (input.read().also { nextChar = it } != -1) {
                buffer.append(nextChar.toChar())
                if (nextChar == '\r'.code || nextChar == '\n'.code && callback != null) {
                    val line = currentLine.toString()
                    processOutputLine(line)
                    currentLine.setLength(0)
                    continue
                }
                currentLine.append(nextChar.toChar())
            }
        } catch (e: IOException) {
            Log.e(TAG, "failed to read stream", e)
        }
    }

    private fun processOutputLine(line: String) {
        callback?.let { it(getProgress(line), getEta(line), line) }
    }

    private fun getProgress(line: String): Float {
        // 1. Detect time ranges from yt-dlp section download: [info] ... Downloading 1 time ranges: 1.0-4.0
        val mRanges = pTimeRanges.matcher(line)
        if (mRanges.find()) {
            val start = mRanges.group(1)?.toFloatOrNull() ?: 0f
            val end = mRanges.group(2)?.toFloatOrNull() ?: 0f
            if (end > start) {
                expectedDuration = max(1.0f, end - start)
            }
        }

        // 2. Detect duration from ffmpeg header if not already set: Duration: 00:00:05.00
        val mDuration = pDuration.matcher(line)
        if (mDuration.find() && expectedDuration <= 0f) {
            val h = mDuration.group(1)?.toIntOrNull() ?: 0
            val m = mDuration.group(2)?.toIntOrNull() ?: 0
            val s = mDuration.group(3)?.toFloatOrNull() ?: 0f
            val total = h * 3600f + m * 60f + s
            if (total > 0f) {
                expectedDuration = total
            }
        }

        // 3. Standard yt-dlp download progress: [download]  12.3% or [download] 100%
        val mDownload = pDownload.matcher(line)
        if (mDownload.find()) {
            return mDownload.group(1)!!.toFloat().also { progress = it }
        }

        // 4. Aria2c progress
        val mAria2c = pAria2c.matcher(line)
        if (mAria2c.find()) {
            return mAria2c.group(1)!!.toFloat().also { progress = it }
        }

        // 5. FFmpeg progress during section extraction / trimming
        val mFFmpeg = pFFmpegTime.matcher(line)
        if (mFFmpeg.find()) {
            val h = mFFmpeg.group(1)?.toIntOrNull() ?: 0
            val m = mFFmpeg.group(2)?.toIntOrNull() ?: 0
            val s = mFFmpeg.group(3)?.toFloatOrNull() ?: 0f
            val currentTime = h * 3600f + m * 60f + s
            lastFFmpegTime = currentTime
            if (expectedDuration > 0f) {
                val pct = ((currentTime / expectedDuration) * 100f).coerceIn(0f, 99f)
                return pct.also { progress = it }
            } else {
                val next = if (progress < 0f) 5f else (progress + 2f).coerceAtMost(95f)
                return next.also { progress = it }
            }
        }

        return progress
    }

    private fun getEta(line: String): Long {
        val mEta = pEta.matcher(line)
        if (mEta.find()) {
            val h = mEta.group(1)?.toIntOrNull() ?: 0
            val m = mEta.group(2)?.toIntOrNull() ?: 0
            val s = mEta.group(3)?.toIntOrNull() ?: 0
            return (h * 3600 + m * 60 + s).toLong().also { eta = it }
        }

        val mAria2c = pAria2c.matcher(line)
        if (mAria2c.find()) {
            return convertToSeconds(
                mAria2c.group(3),
                mAria2c.group(5)
            ).also { eta = it.toLong() }.toLong()
        }

        val mSpeed = pFFmpegSpeed.matcher(line)
        if (mSpeed.find() && expectedDuration > 0f && lastFFmpegTime > 0f) {
            val speed = mSpeed.group(1)?.toFloatOrNull() ?: 0f
            if (speed > 0f) {
                val remainingSec = ((expectedDuration - lastFFmpegTime) / speed).toLong()
                return max(0L, remainingSec).also { eta = it }
            }
        }

        return eta
    }

    private fun convertToSeconds(minutes: String?, seconds: String?): Int {
        if (seconds == null) return 0 else if (minutes == null) return seconds.toInt()
        return minutes.toInt() * 60 + seconds.toInt()
    }

    companion object {
        private val TAG = StreamProcessExtractor::class.java.simpleName
        private const val ETA: Long = -1
        private const val PERCENT = -1.0f
    }
}