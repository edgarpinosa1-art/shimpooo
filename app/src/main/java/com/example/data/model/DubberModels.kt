package com.example.data.model

enum class DubVoiceEngine(
    val label: String,
    val badge: String,
    val description: String
) {
    ANDROID_FIL_PH(
        label = "Filipino Neural HD (Built-in · Multi-Voice)",
        badge = "RECOMMENDED · ZERO WAIT",
        description = "Uses your phone's HD Neural Filipino voices with studio EQ mastering + natural multi-speaker voices (₱0, no big download)."
    ),
    MMS_ONNX_TAGALOG(
        label = "Open-Source MMS-TTS (Tagalog ONNX)",
        badge = "OPEN SOURCE · OFFLINE ONNX",
        description = "Meta facebook/mms-tts-tgl VITS running locally via ONNX Runtime with broadcast mastering (₱0, 100% offline after ~62MB download)."
    )
}

enum class WhisperBrainTier(
    val label: String,
    val shortBadge: String,
    val repoId: String,
    val approxSizeLabel: String,
    val folderName: String
) {
    LARGE_V3_TURBO(
        label = "Whisper Large-v3-Turbo (Latest & Strongest)",
        shortBadge = "LATEST · ~1.0 GB",
        repoId = "csukuangfj/sherpa-onnx-whisper-turbo",
        approxSizeLabel = "~1.0 GB",
        folderName = "whisper-turbo-onnx"
    ),
    SMALL(
        label = "Whisper Small (High Accuracy)",
        shortBadge = "BALANCED · ~374 MB",
        repoId = "csukuangfj/sherpa-onnx-whisper-small",
        approxSizeLabel = "~374 MB",
        folderName = "whisper-small-onnx"
    ),
    BASE(
        label = "Whisper Base (Fast Lightweight)",
        shortBadge = "LIGHT · ~105 MB",
        repoId = "csukuangfj/sherpa-onnx-whisper-base",
        approxSizeLabel = "~105 MB",
        folderName = "whisper-base-onnx"
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
    val backgroundGainDuringSpeech: Float,
    val stripCenterVocals: Boolean = false
) {
    STRIP_VOCALS_KEEP_BGM(
        label = "Vocal Strip (No Orig Voice · Keep BGM)",
        backgroundGainDuringSpeech = 0.03f,
        stripCenterVocals = true
    ),
    SMART_DUCK_20(
        label = "Deep Duck (8% Orig Audio)",
        backgroundGainDuringSpeech = 0.08f,
        stripCenterVocals = false
    ),
    MUTE_ORIGINAL(
        label = "Pure Voiceover (0% Orig)",
        backgroundGainDuringSpeech = 0.0f,
        stripCenterVocals = false
    )
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
    val localeTag: String,
    val isFemaleHint: Boolean? = null,
    val isOfflineHd: Boolean = true
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
    val presetLabel: String = "Natural (1.00x)",
    val detectedPitchHz: Float = 0f,
    val genderTag: String = ""
)
