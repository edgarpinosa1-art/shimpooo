package com.example.data.model

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.FacebookAccent
import com.example.ui.theme.InstagramAccent
import com.example.ui.theme.PinterestAccent
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.TikTokAccent
import com.example.ui.theme.YouTubeAccent

enum class SocialPlatform(
    val displayName: String,
    val badgeTag: String,
    val accentColor: Color
) {
    TIKTOK("TikTok", "TT · NO WATERMARK", TikTokAccent),
    FACEBOOK("Facebook", "FB · REELS & WATCH", FacebookAccent),
    INSTAGRAM("Instagram", "IG · REELS & POSTS", InstagramAccent),
    YOUTUBE("YouTube", "YT · VIDEO & SHORTS", YouTubeAccent),
    PINTEREST("Pinterest", "PIN · VIDEO & IDEA PINS", PinterestAccent),
    UNKNOWN("Unsupported", "PASTE TT / FB / IG / YT / PIN URL", StudioTextSecondary);

    companion object {
        fun detectFromUrl(rawUrl: String): SocialPlatform {
            val lower = rawUrl.trim().lowercase()
            return when {
                lower.contains("tiktok.com") || lower.contains("douyin.com") -> TIKTOK
                lower.contains("facebook.com") || lower.contains("fb.watch") || lower.contains("fb.com") -> FACEBOOK
                lower.contains("instagram.com") || lower.contains("instagr.am") -> INSTAGRAM
                lower.contains("youtube.com") || lower.contains("youtu.be") || lower.contains("youtube-nocookie.com") -> YOUTUBE
                lower.contains("pinterest.") || lower.contains("pin.it") -> PINTEREST
                else -> UNKNOWN
            }
        }
    }
}
