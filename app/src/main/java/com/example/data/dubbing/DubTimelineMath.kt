package com.example.data.dubbing

import com.example.data.model.DubLineItem
import com.example.data.model.VideoTranscript
import java.util.Locale

object DubTimelineMath {

    fun parseTimestampToMs(raw: String): Long? {
        val cleaned = raw.trim().replace(',', '.')
        if (cleaned.isBlank()) return null

        val parts = cleaned.split(':')
        return when (parts.size) {
            3 -> {
                val hours = parts[0].filter(Char::isDigit).toLongOrNull() ?: return null
                val mins = parts[1].filter(Char::isDigit).toLongOrNull() ?: return null
                val secs = parseSecondsPartToMs(parts[2]) ?: return null
                (hours * 3_600_000L) + (mins * 60_000L) + secs
            }
            2 -> {
                val mins = parts[0].filter(Char::isDigit).toLongOrNull() ?: return null
                val secs = parseSecondsPartToMs(parts[1]) ?: return null
                (mins * 60_000L) + secs
            }
            1 -> parseSecondsPartToMs(parts[0])
            else -> null
        }
    }

    private fun parseSecondsPartToMs(rawSec: String): Long? {
        val filtered = rawSec.trim().filter { it.isDigit() || it == '.' }
        if (filtered.isEmpty()) return null
        val dotIdx = filtered.indexOf('.')
        return if (dotIdx == -1) {
            val sec = filtered.toLongOrNull() ?: return null
            sec * 1000L
        } else {
            val sec = filtered.substring(0, dotIdx).ifEmpty { "0" }.toLongOrNull() ?: return null
            val msStr = filtered.substring(dotIdx + 1).filter(Char::isDigit).padEnd(3, '0').take(3)
            val ms = msStr.toLongOrNull() ?: 0L
            (sec * 1000L) + ms
        }
    }

    fun formatMsToTimestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val mins = (safe % 3_600_000L) / 60_000L
        val secs = (safe % 60_000L) / 1000L
        val millis = (safe % 1000L) / 10L
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d.%02d", hours, mins, secs, millis)
        } else {
            String.format(Locale.US, "%02d:%02d.%02d", mins, secs, millis)
        }
    }

    fun fromVideoTranscript(transcript: VideoTranscript): List<DubLineItem> {
        return transcript.segments.mapIndexedNotNull { idx, seg ->
            val start = parseTimestampToMs(seg.startTime) ?: (idx * 3000L)
            val parsedEnd = parseTimestampToMs(seg.endTime)
            val end = if (parsedEnd != null && parsedEnd > start) parsedEnd else start + 3000L
            val text = seg.text.trim()
            if (text.isEmpty()) return@mapIndexedNotNull null
            DubLineItem(
                id = idx + 1L,
                startMs = start,
                endMs = end,
                speaker = seg.speaker.ifBlank { "Speaker 1" },
                originalText = "",
                filipinoText = text
            )
        }
    }

    fun parseSrtOrTimedScript(rawInput: String): List<DubLineItem> {
        val normalized = rawInput.replace("\r\n", "\n").trim()
        if (normalized.isBlank()) return emptyList()

        // 1) Try standard SRT block parsing
        val srtArrowRegex = Regex(
            """(\d{1,2}:\d{2}(?::\d{2})?(?:[.,]\d{1,3})?)\s*-->\s*(\d{1,2}:\d{2}(?::\d{2})?(?:[.,]\d{1,3})?)"""
        )
        if (srtArrowRegex.containsMatchIn(normalized)) {
            val blocks = normalized.split(Regex("""\n\s*\n+"""))
            val items = mutableListOf<DubLineItem>()
            var nextId = 1L
            for (block in blocks) {
                val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.isEmpty()) continue
                val timeLineIdx = lines.indexOfFirst { srtArrowRegex.containsMatchIn(it) }
                if (timeLineIdx == -1) continue
                val match = srtArrowRegex.find(lines[timeLineIdx]) ?: continue
                val startMs = parseTimestampToMs(match.groupValues[1]) ?: continue
                val endMs = parseTimestampToMs(match.groupValues[2])?.coerceAtLeast(startMs + 300L)
                    ?: (startMs + 2500L)
                val body = lines.drop(timeLineIdx + 1).joinToString(" ").trim()
                if (body.isNotEmpty()) {
                    items.add(
                        DubLineItem(
                            id = nextId++,
                            startMs = startMs,
                            endMs = endMs,
                            speaker = "Speaker 1",
                            originalText = "",
                            filipinoText = body
                        )
                    )
                }
            }
            if (items.isNotEmpty()) return items
        }

        // 2) Try bracketed / inline timestamps: [00:01 - 00:04] Speaker: Text OR 00:01 - 00:04 Text
        val lineRegex = Regex(
            """^\[?\s*(\d{1,2}:\d{2}(?::\d{2})?(?:[.,]\d{1,3})?)\s*(?:-|→|to)\s*(\d{1,2}:\d{2}(?::\d{2})?(?:[.,]\d{1,3})?)\s*]?\s*(?:([^:|\n]{1,24}):\s*|\|\s*)?(.+)$"""
        )
        val parsedLines = mutableListOf<DubLineItem>()
        var idCounter = 1L
        for (rawLine in normalized.lines()) {
            val line = rawLine.trim()
            if (line.isBlank()) continue
            val m = lineRegex.find(line) ?: continue
            val startMs = parseTimestampToMs(m.groupValues[1]) ?: continue
            val endMs = parseTimestampToMs(m.groupValues[2])?.coerceAtLeast(startMs + 300L)
                ?: (startMs + 2500L)
            val speaker = m.groupValues[3].trim().ifBlank { "Speaker 1" }
            val text = m.groupValues[4].trim()
            if (text.isNotEmpty()) {
                parsedLines.add(
                    DubLineItem(
                        id = idCounter++,
                        startMs = startMs,
                        endMs = endMs,
                        speaker = speaker,
                        originalText = "",
                        filipinoText = text
                    )
                )
            }
        }
        return parsedLines
    }

    /**
     * Natural speech pacing: never slows down short clips (leaves natural breathing pauses),
     * absorbs <=12% minor overflow cleanly, and gently compresses long lines up to 1.28x.
     */
    fun computeTempoRatioForWindow(ttsDurationMs: Long, targetWindowMs: Long): Float {
        if (ttsDurationMs <= 0L || targetWindowMs <= 0L) return 1.0f
        val rawRatio = ttsDurationMs.toFloat() / targetWindowMs.toFloat()
        return when {
            rawRatio <= 1.12f -> 1.0f
            else -> rawRatio.coerceAtMost(1.28f)
        }
    }

    /**
     * Strips transient emotional descriptors like "(angry)" or "(whispering)" while preserving
     * canonical character identity and gender hints ("(Male)" / "(Female)"), so the same character
     * keeps one locked voice from start to end of the video.
     */
    fun canonicalizeSpeakerName(raw: String): String {
        val trimmed = raw.trim().ifBlank { "Speaker 1" }
        val lower = trimmed.lowercase(Locale.US)
        val parenMatches = Regex("""\(([^)]+)\)""").findAll(trimmed).toList()
        var cleaned = trimmed
        for (match in parenMatches) {
            val inner = match.groupValues[1].trim().lowercase(Locale.US)
            val isGenderMarker = inner in setOf("male", "female", "man", "woman", "boy", "girl", "lalaki", "babae")
            if (!isGenderMarker) {
                cleaned = cleaned.replace(match.value, "")
            }
        }
        val normalized = cleaned.replace(Regex("""\s+"""), " ").trim().trim(':', '-', ',')
        return normalized.ifBlank {
            if (lower.contains("female")) "Speaker (Female)"
            else if (lower.contains("male")) "Speaker (Male)"
            else "Speaker 1"
        }
    }

    fun describePitchPreset(relativePitch: Float): String {
        return when {
            relativePitch <= 0.92f -> "Deep"
            relativePitch <= 0.97f -> "Warm"
            relativePitch <= 1.03f -> "Natural"
            relativePitch <= 1.09f -> "Light"
            else -> "Bright"
        }
    }

    /**
     * Assigns natural gender-matched voice ID + subtle realistic pitch per detected character.
     */
    fun resolveSpeakerVoiceAssignment(
        speaker: String,
        distinctSpeakers: List<String>,
        basePitch: Float,
        availableVoices: List<com.example.data.model.DubVoiceOption>,
        selectedVoiceId: String,
        detectedPitchHz: Float = 0f
    ): com.example.data.model.SpeakerVoiceProfile {
        val canonical = canonicalizeSpeakerName(speaker)
        val normalizedKey = canonical.lowercase(Locale.US)
        val normalizedOrder = distinctSpeakers
            .map { canonicalizeSpeakerName(it).lowercase(Locale.US) }
            .distinct()
        val speakerIdx = normalizedOrder.indexOf(normalizedKey).let { if (it >= 0) it else 0 }

        val maleHints = listOf("male", "man", "boy", "father", "dad", "kuya", "lalaki", "guy", "king")
        val femaleHints = listOf("female", "woman", "girl", "mother", "mom", "ate", "babae", "lady", "queen")
        val isFemale = femaleHints.any { normalizedKey.contains(it) } || (!maleHints.any { normalizedKey.contains(it) } && detectedPitchHz >= 182f)
        val isMale = maleHints.any { normalizedKey.contains(it) } || (!isFemale && detectedPitchHz in 65f..162f)
        val genderTag = when {
            isFemale -> "Female"
            isMale -> "Male"
            else -> ""
        }

        val orderedVoices = buildList {
            if (selectedVoiceId.isNotBlank()) {
                availableVoices.firstOrNull { it.id == selectedVoiceId }?.let { add(it) }
            }
            availableVoices.forEach { v ->
                if (v.id.isNotBlank() && none { it.id == v.id }) add(v)
            }
        }

        // Prefer matching gender voice option if device provides multiple distinct voices
        val assignedVoice = when {
            isFemale && orderedVoices.any { it.isFemaleHint == true } -> {
                val females = orderedVoices.filter { it.isFemaleHint == true }
                females[speakerIdx % females.size]
            }
            isMale && orderedVoices.any { it.isFemaleHint == false } -> {
                val males = orderedVoices.filter { it.isFemaleHint == false }
                males[speakerIdx % males.size]
            }
            orderedVoices.isNotEmpty() -> orderedVoices[speakerIdx % orderedVoices.size]
            else -> null
        }

        // Keep pitch shifts natural & realistic (smaller shift when native distinct voices already differ!)
        val hasSeparateVoiceGender = assignedVoice?.isFemaleHint != null
        val relativePitch = when {
            isFemale && hasSeparateVoiceGender -> if (speakerIdx % 2 == 0) 1.00f else 1.04f
            isMale && hasSeparateVoiceGender -> if (speakerIdx % 2 == 0) 0.98f else 0.94f
            isFemale -> if (speakerIdx % 2 == 0) 1.07f else 1.12f
            isMale -> if (speakerIdx % 2 == 0) 0.94f else 0.90f
            else -> floatArrayOf(1.00f, 1.07f, 0.93f, 1.12f, 0.97f)[speakerIdx % 5]
        }

        val finalPitch = (basePitch * relativePitch).coerceIn(0.86f, 1.18f)
        val assignedId = assignedVoice?.id ?: selectedVoiceId
        val hzNote = if (detectedPitchHz > 0f) " · ~${detectedPitchHz.toInt()}Hz" else ""
        return com.example.data.model.SpeakerVoiceProfile(
            speakerKey = canonical,
            pitchMultiplier = finalPitch,
            androidVoiceId = assignedId,
            lockedIndex = speakerIdx,
            presetLabel = "${describePitchPreset(relativePitch)} (${String.format(Locale.US, "%.2fx", finalPitch)}$hzNote)",
            detectedPitchHz = detectedPitchHz,
            genderTag = genderTag
        )
    }

    /**
     * Builds or updates a persistent character voice cast map so every character keeps their
     * exact locked voice from the beginning of the video to the very end, even across edits or chunks.
     */
    fun buildLockedCharacterCast(
        lines: List<DubLineItem>,
        existingCast: Map<String, com.example.data.model.SpeakerVoiceProfile>,
        basePitch: Float,
        availableVoices: List<com.example.data.model.DubVoiceOption>,
        selectedVoiceId: String,
        detectedPitchHzMap: Map<String, Float> = emptyMap()
    ): Map<String, com.example.data.model.SpeakerVoiceProfile> {
        val canonicalSpeakers = lines
            .map { canonicalizeSpeakerName(it.speaker) }
            .distinct()
        if (canonicalSpeakers.isEmpty()) return emptyMap()

        val result = linkedMapOf<String, com.example.data.model.SpeakerVoiceProfile>()
        for (speaker in canonicalSpeakers) {
            val existing = existingCast[speaker]
            if (existing != null) {
                result[speaker] = existing
            } else {
                result[speaker] = resolveSpeakerVoiceAssignment(
                    speaker = speaker,
                    distinctSpeakers = canonicalSpeakers,
                    basePitch = basePitch,
                    availableVoices = availableVoices,
                    selectedVoiceId = selectedVoiceId,
                    detectedPitchHz = detectedPitchHzMap[speaker] ?: 0f
                )
            }
        }
        return result
    }

    fun cycleCharacterVoiceProfile(
        current: com.example.data.model.SpeakerVoiceProfile,
        availableVoices: List<com.example.data.model.DubVoiceOption>
    ): com.example.data.model.SpeakerVoiceProfile {
        val pitchSteps = listOf(0.90f, 0.95f, 1.00f, 1.06f, 1.12f)
        val closestIdx = pitchSteps.indices.minByOrNull { kotlin.math.abs(pitchSteps[it] - current.pitchMultiplier) } ?: 2
        val nextPitch = pitchSteps[(closestIdx + 1) % pitchSteps.size]

        val voiceIds = availableVoices.mapNotNull { it.id.takeIf(String::isNotBlank) }
        val nextVoiceId = if (voiceIds.size > 1) {
            val curV = voiceIds.indexOf(current.androidVoiceId).let { if (it >= 0) it else 0 }
            voiceIds[(curV + 1) % voiceIds.size]
        } else {
            current.androidVoiceId
        }
        val hzNote = if (current.detectedPitchHz > 0f) " · ~${current.detectedPitchHz.toInt()}Hz" else ""
        return current.copy(
            pitchMultiplier = nextPitch,
            androidVoiceId = nextVoiceId,
            presetLabel = "${describePitchPreset(nextPitch)} (${String.format(Locale.US, "%.2fx", nextPitch)}$hzNote)"
        )
    }

    /**
     * Merges chunked dubbing script windows for long-form videos (>8 minutes),
     * pruning out-of-window drift, deduplicating boundary lines, and avoiding overlaps.
     */
    fun mergeDubScriptChunks(
        chunkResults: List<List<DubLineItem>>,
        windows: List<Pair<Long, Long>>
    ): List<DubLineItem> {
        val flattened = mutableListOf<DubLineItem>()
        val isMultiChunk = windows.size > 1

        chunkResults.forEachIndexed { chunkIdx, lines ->
            val window = windows.getOrNull(chunkIdx)
            val winStart = window?.first ?: 0L
            val winEnd = window?.second ?: Long.MAX_VALUE

            lines.forEach { item ->
                if (isMultiChunk && winEnd > winStart) {
                    val outsideStart = item.startMs < (winStart - 1500L)
                    val outsideEnd = item.startMs > (winEnd + 1500L)
                    if (outsideStart || outsideEnd) return@forEach
                }
                flattened.add(item.copy(speaker = canonicalizeSpeakerName(item.speaker)))
            }
        }

        val sorted = flattened.sortedBy { it.startMs }
        val deduped = mutableListOf<DubLineItem>()
        for (line in sorted) {
            val prev = deduped.lastOrNull()
            if (prev == null) {
                deduped.add(line)
                continue
            }
            val sameStartWindow = kotlin.math.abs(line.startMs - prev.startMs) < 900L
            val normalizedPrev = prev.filipinoText.trim().lowercase(Locale.US)
            val normalizedCurr = line.filipinoText.trim().lowercase(Locale.US)
            val duplicateText = normalizedPrev == normalizedCurr ||
                (normalizedCurr.length > 10 && normalizedPrev.contains(normalizedCurr)) ||
                (normalizedPrev.length > 10 && normalizedCurr.contains(normalizedPrev))

            if (sameStartWindow && duplicateText) {
                continue
            }

            // Ensure previous segment ends cleanly before next speaker starts
            if (prev.endMs > line.startMs - 40L && line.startMs > prev.startMs + 320L) {
                deduped[deduped.lastIndex] = prev.copy(endMs = (line.startMs - 40L).coerceAtLeast(prev.startMs + 280L))
            }
            deduped.add(line)
        }

        return deduped.mapIndexed { idx, item ->
            item.copy(id = idx + 1L)
        }
    }
}
