package com.deniscerri.ytdl.util

import java.io.File
import java.util.Locale
import java.util.regex.Pattern

object SubtitleAdjuster {

    private val CUE_TIMINGS_LINE_PATTERN = Pattern.compile(
        """^((?:\d{1,2}:)?\d{2}:\d{2}[.,]\d{3})\s*-->\s*((?:\d{1,2}:)?\d{2}:\d{2}[.,]\d{3})(.*)$"""
    )
    private val INLINE_TIMESTAMP_PATTERN = Pattern.compile("""<((?:\d{1,2}:)?\d{2}:\d{2}[.,]\d{3})>""")

    fun parseTimestampMs(timeStr: String): Long {
        val clean = timeStr.trim().replace(',', '.')
        val parts = clean.split(":")
        return when (parts.size) {
            3 -> {
                val hours = parts[0].toLongOrNull() ?: 0L
                val minutes = parts[1].toLongOrNull() ?: 0L
                val secParts = parts[2].split(".")
                val seconds = secParts[0].toLongOrNull() ?: 0L
                val ms = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L else 0L
                (hours * 3600 + minutes * 60 + seconds) * 1000 + ms
            }
            2 -> {
                val minutes = parts[0].toLongOrNull() ?: 0L
                val secParts = parts[1].split(".")
                val seconds = secParts[0].toLongOrNull() ?: 0L
                val ms = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L else 0L
                (minutes * 60 + seconds) * 1000 + ms
            }
            1 -> {
                val secParts = parts[0].split(".")
                val seconds = secParts[0].toLongOrNull() ?: 0L
                val ms = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L else 0L
                seconds * 1000 + ms
            }
            else -> 0L
        }
    }

    fun formatVttTimestamp(ms: Long): String {
        val totalSec = ms / 1000
        val millis = ms % 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    fun formatSrtTimestamp(ms: Long): String {
        val totalSec = ms / 1000
        val millis = ms % 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    fun parseTrimRange(downloadSections: String): Pair<Long, Long>? {
        val firstSection = downloadSections.split(";").firstOrNull { it.isNotBlank() } ?: return null
        val cleanSpec = firstSection.split(" ")[0].trim().removePrefix("*")
        val dashIdx = cleanSpec.lastIndexOf('-')
        if (dashIdx <= 0) return null
        val startStr = cleanSpec.substring(0, dashIdx).trim()
        val endStr = cleanSpec.substring(dashIdx + 1).trim()
        val startMs = parseTimestampMs(startStr)
        val endMs = if (endStr.equals("inf", ignoreCase = true)) Long.MAX_VALUE else parseTimestampMs(endStr)
        return Pair(startMs, endMs)
    }

    fun adjustSubtitleFile(file: File, startTrimMs: Long, endTrimMs: Long): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val ext = file.extension.lowercase()
        return when (ext) {
            "vtt" -> adjustVtt(file, startTrimMs, endTrimMs)
            "srt" -> adjustSrt(file, startTrimMs, endTrimMs)
            else -> false
        }
    }

    private fun adjustVtt(file: File, startTrimMs: Long, endTrimMs: Long): Boolean {
        val lines = file.readLines()
        val outputLines = mutableListOf<String>()
        var i = 0
        val maxDurationMs = if (endTrimMs == Long.MAX_VALUE) Long.MAX_VALUE else (endTrimMs - startTrimMs)

        // Read header until first cue
        while (i < lines.size) {
            val line = lines[i]
            if (line.contains("-->")) {
                break
            }
            outputLines.add(line)
            i++
        }

        // If we backed up into a cue identifier above "-->", back up 1 line
        if (outputLines.isNotEmpty() && outputLines.last().isNotBlank() && !outputLines.last().startsWith("WEBVTT") && !outputLines.last().contains(":")) {
            outputLines.removeAt(outputLines.size - 1)
            processVttCues(lines, i - 1, outputLines, startTrimMs, endTrimMs, maxDurationMs)
        } else {
            processVttCues(lines, i, outputLines, startTrimMs, endTrimMs, maxDurationMs)
        }

        file.writeText(outputLines.joinToString("\n"))
        return true
    }

    private fun processVttCues(
        lines: List<String>,
        startIndex: Int,
        outputLines: MutableList<String>,
        startTrimMs: Long,
        endTrimMs: Long,
        maxDurationMs: Long
    ) {
        var i = startIndex
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }

            var cueId: String? = null
            var timingLine = line
            if (!line.contains("-->") && i + 1 < lines.size && lines[i + 1].contains("-->")) {
                cueId = line
                i++
                timingLine = lines[i]
            }

            val matcher = CUE_TIMINGS_LINE_PATTERN.matcher(timingLine.trim())
            if (matcher.matches()) {
                val startStr = matcher.group(1)!!
                val endStr = matcher.group(2)!!
                val settings = matcher.group(3) ?: ""

                val origStartMs = parseTimestampMs(startStr)
                val origEndMs = parseTimestampMs(endStr)

                val payloadLines = mutableListOf<String>()
                i++
                while (i < lines.size && lines[i].isNotBlank()) {
                    payloadLines.add(lines[i])
                    i++
                }

                if (origEndMs > startTrimMs && origStartMs < endTrimMs) {
                    val shiftedStartMs = maxOf(0L, origStartMs - startTrimMs)
                    val shiftedEndMs = minOf(maxDurationMs, origEndMs - startTrimMs)

                    if (shiftedEndMs > shiftedStartMs) {
                        outputLines.add("")
                        if (cueId != null) {
                            outputLines.add(cueId)
                        }
                        val newTimingLine = "${formatVttTimestamp(shiftedStartMs)} --> ${formatVttTimestamp(shiftedEndMs)}$settings"
                        outputLines.add(newTimingLine)

                        for (pLine in payloadLines) {
                            val shiftedPLine = shiftInlineTimestamps(pLine, startTrimMs, maxDurationMs)
                            outputLines.add(shiftedPLine)
                        }
                    }
                }
            } else {
                i++
            }
        }
    }

    private fun adjustSrt(file: File, startTrimMs: Long, endTrimMs: Long): Boolean {
        val lines = file.readLines()
        val outputLines = mutableListOf<String>()
        var i = 0
        var srtIndex = 1
        val maxDurationMs = if (endTrimMs == Long.MAX_VALUE) Long.MAX_VALUE else (endTrimMs - startTrimMs)

        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }

            var timingLine = line
            if (!line.contains("-->") && i + 1 < lines.size && lines[i + 1].contains("-->")) {
                i++
                timingLine = lines[i]
            }

            val matcher = CUE_TIMINGS_LINE_PATTERN.matcher(timingLine.trim())
            if (matcher.matches()) {
                val startStr = matcher.group(1)!!
                val endStr = matcher.group(2)!!

                val origStartMs = parseTimestampMs(startStr)
                val origEndMs = parseTimestampMs(endStr)

                val payloadLines = mutableListOf<String>()
                i++
                while (i < lines.size && lines[i].isNotBlank()) {
                    payloadLines.add(lines[i])
                    i++
                }

                if (origEndMs > startTrimMs && origStartMs < endTrimMs) {
                    val shiftedStartMs = maxOf(0L, origStartMs - startTrimMs)
                    val shiftedEndMs = minOf(maxDurationMs, origEndMs - startTrimMs)

                    if (shiftedEndMs > shiftedStartMs) {
                        outputLines.add((srtIndex++).toString())
                        val newTimingLine = "${formatSrtTimestamp(shiftedStartMs)} --> ${formatSrtTimestamp(shiftedEndMs)}"
                        outputLines.add(newTimingLine)
                        outputLines.addAll(payloadLines)
                        outputLines.add("")
                    }
                }
            } else {
                i++
            }
        }

        file.writeText(outputLines.joinToString("\n"))
        return true
    }

    private fun shiftInlineTimestamps(line: String, startTrimMs: Long, maxDurationMs: Long): String {
        val matcher = INLINE_TIMESTAMP_PATTERN.matcher(line)
        val sb = StringBuffer()
        while (matcher.find()) {
            val tagTimeStr = matcher.group(1)!!
            val origMs = parseTimestampMs(tagTimeStr)
            val shiftedMs = minOf(maxDurationMs, maxOf(0L, origMs - startTrimMs))
            matcher.appendReplacement(sb, "<${formatVttTimestamp(shiftedMs)}>")
        }
        matcher.appendTail(sb)
        return sb.toString()
    }
}
