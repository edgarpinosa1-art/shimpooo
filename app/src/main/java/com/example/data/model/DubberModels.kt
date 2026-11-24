package com.example.data.model

enum class DubVoiceEngine(
    val label: String,
    val badge: String,
    val description: String
) {
    MMS_ONNX_TAGALOG(
        label = "Open-Source MMS-TTS (Tagalog ONNX)",
        badge = "OPEN SOURCE · OFFLINE ONNX",
        description = "Meta facebook/mms-tts-tgl VITS running locally via ONNX Runtime (₱0, 100% offline after 1-time ~62MB model download)."
    ),
    ANDROID_FIL_PH(
        label = "Android Neural Filipino (fil-PH)",
        badge = "BUILT-IN · ZERO DOWNLOAD",
        description = "Device Speech Services Filipino voice (supports Taglish, multiple voices, & zero extra storage)."
    )
}

enum class DubSourceLanguage(
    val label: String,
    val promptHint: String
) {
    AUTO_DETECT("Auto (EN / JA / Any)", "Auto-detect the spoken language (English, Japanese, or other) and translate to natural Filipino/Tagalog."),
    JAPANESE("Japanese (Anime / Vlog)", "The video speech is Japanese. Translate idioms, anime expressions, and honorifics into natural Tagalog/Filipino matching the speaker's energy and timing."),
    ENGLISH("English", "The video speech is English. Translate cleanly into natural conversational Filipino/Tagalog matching the speaker's cadence and timing."),
    OTHER("Other Foreign Language", "Detect the foreign speech and translate naturally into conversational Filipino/Tagalog.")
}

enum class DubAudioDuckingMode(
    val label: String,
    val backgroundGainDuringSpeech: Float
) {
    SMART_DUCK_20("Smart Duck (20% BGM/SFX)", 0.20f),
    LIGHT_DUCK_10("Deep Duck (10% BGM/SFX)", 0.10f),
    MUTE_ORIGINAL("Pure Voiceover (0% Orig)", 0.0f)
}

data class DubLineItem(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val speaker: String,
    val originalText: String,
    val filipinoText: String
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(300L)
}

data class DubVoiceOption(
    val id: String,
    val label: String,
    val localeTag: String
)

enum class MmsModelStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    READY,
    ERROR
}

data class DubbedVideoResult(
    val localFilePath: String,
    val contentUriString: String,
    val gallerySaved: Boolean,
    val fileSizeBytes: Long,
    val fileName: String,
    val engineUsed: String,
    val segmentCount: Int
)

data class SpeakerVoiceProfile(
    val speakerKey: String,
    val pitchMultiplier: Float,
    val androidVoiceId: String,
    val lockedIndex: Int = 0,
    val presetLabel: String = "Natural (1.00x)"
)
