package com.example.data.model

enum class TranscriptLanguageMode(
    val label: String,
    val directive: String
) {
    ORIGINAL_VERBATIM(
        "Verbatim Original",
        "Transcribe word-for-word in the exact spoken language(s) heard in the video (preserve Tagalog, English, or Taglish code-switching accurately)."
    ),
    ENGLISH(
        "English",
        "Transcribe and translate cleanly into natural English while preserving accurate speaker turns and timestamps."
    ),
    TAGALOG_TAGLISH(
        "Tagalog / Taglish",
        "Transcribe and translate naturally into clear conversational Tagalog / Taglish while preserving exact timestamps."
    ),
    FILIPINO_DUB_SYNC(
        "Filipino Dub (Lip-Sync)",
        "Transcribe any English, Japanese, or foreign dialogue and translate into natural Filipino/Tagalog phrased with matching syllable length and exact start/end millisecond timing so each line fits the speaker's mouth movements."
    ),
    BILINGUAL(
        "Bilingual (Orig + EN)",
        "For each timestamped segment, provide the original verbatim speech followed by an English translation in parentheses if not already English."
    )
}

data class TranscriptSegment(
    val startTime: String,
    val endTime: String,
    val speaker: String,
    val text: String
)

data class VideoTranscript(
    val id: Long = 0L,
    val videoTitle: String,
    val sourceUriString: String,
    val geminiModelUsed: String,
    val languageMode: TranscriptLanguageMode,
    val detectedLanguage: String,
    val overallSummary: String,
    val segments: List<TranscriptSegment>,
    val fullPlainTranscript: String,
    val createdAt: Long = System.currentTimeMillis()
)
