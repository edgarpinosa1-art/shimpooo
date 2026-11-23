package com.example.data.model

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.FacebookAccent
import com.example.ui.theme.InstagramAccent
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.TikTokAccent

enum class SocialPlatform(
    val displayName: String,
    val badgeTag: String,
    val accentColor: Color
) {
    TIKTOK("TikTok", "TT · NO WATERMARK", TikTokAccent),
    FACEBOOK("Facebook", "FB · REELS & WATCH", FacebookAccent),
    INSTAGRAM("Instagram", "IG · REELS & POSTS", InstagramAccent),
    UNKNOWN("Unsupported", "PASTE TIKTOK / FB / IG URL", StudioTextSecondary);

    companion object {
        fun detectFromUrl(rawUrl: String): SocialPlatform {
            val lower = rawUrl.trim().lowercase()
            return when {
                lower.contains("tiktok.com") || lower.contains("douyin.com") -> TIKTOK
                lower.contains("facebook.com") || lower.contains("fb.watch") || lower.contains("fb.com") -> FACEBOOK
                lower.contains("instagram.com") || lower.contains("instagr.am") -> INSTAGRAM
                else -> UNKNOWN
            }
        }
    }
}
