package com.example.data.dubbing

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.data.model.DubVoiceOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

class AndroidTtsSynthesizer(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var initialized: Boolean = false

    suspend fun ensureInitialized(): Boolean = withContext(Dispatchers.Main) {
        if (initialized && tts != null) return@withContext true

        suspendCancellableCoroutine { cont ->
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts = engine
                    val filLocale = Locale.forLanguageTag("fil-PH")
                    val res = engine?.setLanguage(filLocale)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        engine?.setLanguage(Locale("tl", "PH"))
                    }
                    initialized = true
                    if (cont.isActive) cont.resume(true)
                } else {
                    initialized = false
                    if (cont.isActive) cont.resume(false)
                }
            }
            cont.invokeOnCancellation {
                runCatching { engine?.shutdown() }
            }
        }
    }

    suspend fun getAvailableFilipinoVoices(): List<DubVoiceOption> {
        if (!ensureInitialized()) return emptyList()
        val engine = tts ?: return emptyList()
        val allVoices = runCatching { engine.voices }.getOrNull().orEmpty()
        val filVoices = allVoices.filter { voice ->
            val lang = voice.locale.language.lowercase(Locale.US)
            val tag = voice.locale.toLanguageTag().lowercase(Locale.US)
            lang == "fil" || lang == "tl" || tag.contains("fil-ph") || tag.contains("tl-ph")
        }.sortedWith(
            compareBy<android.speech.tts.Voice>(
                { it.isNetworkConnectionRequired }, // Offline first (zero latency/fail)
                { -it.quality },
                { it.name }
            )
        )

        val mapped = filVoices.mapIndexed { idx, voice ->
            val offline = !voice.isNetworkConnectionRequired
            val hdTag = if (voice.quality >= android.speech.tts.Voice.QUALITY_HIGH) "HD" else "Std"
            val qualityBadge = if (offline) "Offline $hdTag" else "Online Neural"
            val lowerName = voice.name.lowercase(Locale.US)
            val features = runCatching { voice.features }.getOrNull().orEmpty().joinToString(",").lowercase(Locale.US)
            val femaleHint: Boolean? = when {
                lowerName.contains("female") || features.contains("female") -> true
                lowerName.contains("male") || features.contains("male") -> false
                lowerName.contains("-cfc") || lowerName.contains("-fid") -> true
                lowerName.contains("-fie") || lowerName.contains("-ism") -> false
                else -> null
            }
            val genderLabel = when (femaleHint) {
                true -> " · Female"
                false -> " · Male"
                null -> ""
            }
            DubVoiceOption(
                id = voice.name,
                label = "Filipino Voice ${idx + 1} ($qualityBadge$genderLabel)",
                localeTag = voice.locale.toLanguageTag(),
                isFemaleHint = femaleHint,
                isOfflineHd = offline
            )
        }
        return if (mapped.isNotEmpty()) {
            mapped
        } else {
            listOf(DubVoiceOption(id = "", label = "System Default (fil-PH)", localeTag = "fil-PH"))
        }
    }

    suspend fun synthesizeToWav(
        text: String,
        voiceId: String,
        pitch: Float,
        speechRate: Float,
        destWavFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        val primary = synthesizeSinglePass(text, voiceId, pitch, speechRate, destWavFile)
        if (primary.isSuccess || voiceId.isBlank()) {
            return@withContext primary
        }
        // Fallback once with system default fil-PH voice so synthesis never breaks mid-video
        synthesizeSinglePass(text, "", pitch, speechRate, destWavFile)
    }

    private suspend fun synthesizeSinglePass(
        text: String,
        voiceId: String,
        pitch: Float,
        speechRate: Float,
        destWavFile: File
    ): Result<File> {
        if (!ensureInitialized()) {
            return Result.failure(
                IllegalStateException("Hindi ma-initialize ang Android Speech Services sa device na ito.")
            )
        }
        val engine = tts ?: return Result.failure(
            IllegalStateException("TextToSpeech engine unavailable.")
        )

        if (voiceId.isNotBlank()) {
            val selected = runCatching { engine.voices?.firstOrNull { it.name == voiceId } }.getOrNull()
            if (selected != null) {
                engine.voice = selected
            }
        } else {
            val filLocale = Locale.forLanguageTag("fil-PH")
            val langRes = engine.setLanguage(filLocale)
            if (langRes == TextToSpeech.LANG_MISSING_DATA || langRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale("tl", "PH"))
            }
        }

        engine.setPitch(pitch.coerceIn(0.80f, 1.25f))
        engine.setSpeechRate(speechRate.coerceIn(0.82f, 1.32f))

        val utteranceId = "dub_${UUID.randomUUID()}"
        val cleanText = text
            .replace(Regex("""\[[^\]]+]"""), " ")
            .replace("&", " at ")
            .replace("%", " porsyento ")
            .replace("₱", " piso ")
            .replace("$", " dolyar ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { text }

        return suspendCancellableCoroutine { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(uid: String?) = Unit

                override fun onDone(uid: String?) {
                    if (uid == utteranceId && cont.isActive) {
                        if (destWavFile.exists() && destWavFile.length() > 44L) {
                            cont.resume(Result.success(destWavFile))
                        } else {
                            cont.resume(
                                Result.failure(IllegalStateException("Empty WAV generated for line."))
                            )
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(uid: String?) {
                    if (uid == utteranceId && cont.isActive) {
                        cont.resume(
                            Result.failure(
                                IllegalStateException("Android TTS failed. Siguraduhing may Filipino voice pack sa TTS settings.")
                            )
                        )
                    }
                }

                override fun onError(uid: String?, errorCode: Int) {
                    if (uid == utteranceId && cont.isActive) {
                        cont.resume(
                            Result.failure(
                                IllegalStateException("Android TTS error ($errorCode). I-download ang Filipino voice sa Android TTS settings.")
                            )
                        )
                    }
                }
            })

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            val code = engine.synthesizeToFile(cleanText, params, destWavFile, utteranceId)
            if (code != TextToSpeech.SUCCESS && cont.isActive) {
                cont.resume(
                    Result.failure(IllegalStateException("Hindi masimulan ang TTS synthesis (code $code)."))
                )
            }
        }
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
        initialized = false
    }
}
