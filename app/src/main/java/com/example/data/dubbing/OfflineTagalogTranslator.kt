package com.example.data.dubbing

import android.util.Log
import com.example.data.model.DubLineItem
import com.example.data.model.DubSourceLanguage
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "OfflineTagalogTranslator"

/**
 * 100% Free On-Device Open/Free Translation Engine using Google ML Kit Language ID + Tagalog
 * offline translation models (zero API keys, zero quotas, works completely offline after 1-time ~30MB model pull).
 */
class OfflineTagalogTranslator {

    suspend fun translateLinesToTagalog(
        lines: List<DubLineItem>,
        sourceHint: DubSourceLanguage = DubSourceLanguage.AUTO_DETECT,
        onStageUpdate: (String) -> Unit = {}
    ): Result<List<DubLineItem>> = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Walang linya na ita-translate."))
        }

        try {
            val sampleText = lines.take(8).joinToString(" ") {
                it.originalText.ifBlank { it.filipinoText }
            }.trim()

            onStageUpdate("Detecting source language (Free On-Device ML Kit)...")
            val detectedCode = when (sourceHint) {
                DubSourceLanguage.JAPANESE -> TranslateLanguage.JAPANESE
                DubSourceLanguage.ENGLISH -> TranslateLanguage.ENGLISH
                else -> detectSupportedSourceLanguage(sampleText)
            }

            if (detectedCode == TranslateLanguage.TAGALOG) {
                // Already Tagalog! Just normalize cleanly
                val alreadyFil = lines.map { item ->
                    val text = item.filipinoText.ifBlank { item.originalText }
                    item.copy(filipinoText = text, originalText = item.originalText.ifBlank { text })
                }
                return@withContext Result.success(alreadyFil)
            }

            onStageUpdate("Preparing offline ${detectedCode.uppercase(Locale.US)} → Tagalog translator (₱0)...")
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(detectedCode)
                .setTargetLanguage(TranslateLanguage.TAGALOG)
                .build()

            val translator = Translation.getClient(options)
            try {
                val conditions = DownloadConditions.Builder().build()
                translator.downloadModelIfNeeded(conditions).awaitResult()

                val translatedLines = ArrayList<DubLineItem>(lines.size)
                for ((idx, line) in lines.withIndex()) {
                    if (idx == 0 || idx % 8 == 0) {
                        onStageUpdate("Translating to Filipino offline (${idx + 1}/${lines.size})...")
                    }
                    val srcText = line.originalText.ifBlank { line.filipinoText }.trim()
                    val tagalog = if (srcText.isBlank()) {
                        ""
                    } else {
                        runCatching { translator.translate(srcText).awaitResult() }
                            .getOrDefault(srcText)
                    }
                    translatedLines.add(
                        line.copy(
                            originalText = srcText,
                            filipinoText = tagalog.ifBlank { srcText }
                        )
                    )
                }
                Result.success(translatedLines)
            } finally {
                runCatching { translator.close() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Offline ML Kit translation failed", e)
            Result.failure(e)
        }
    }

    private suspend fun detectSupportedSourceLanguage(sample: String): String {
        if (sample.isBlank()) return TranslateLanguage.ENGLISH
        val identifier = LanguageIdentification.getClient()
        return try {
            val bcp47 = identifier.identifyLanguage(sample).awaitResult()
            val mapped = TranslateLanguage.fromLanguageTag(bcp47)
            when {
                mapped == TranslateLanguage.TAGALOG -> TranslateLanguage.TAGALOG
                mapped != null -> mapped
                bcp47.startsWith("ja", ignoreCase = true) -> TranslateLanguage.JAPANESE
                bcp47.startsWith("ko", ignoreCase = true) -> TranslateLanguage.KOREAN
                bcp47.startsWith("zh", ignoreCase = true) -> TranslateLanguage.CHINESE
                else -> TranslateLanguage.ENGLISH
            }
        } catch (_: Exception) {
            TranslateLanguage.ENGLISH
        } finally {
            runCatching { identifier.close() }
        }
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { res ->
            if (cont.isActive) cont.resume(res)
        }
        addOnFailureListener { err ->
            if (cont.isActive) cont.resumeWithException(err)
        }
        addOnCanceledListener {
            if (cont.isActive) cont.cancel()
        }
    }
}
