package com.prismo.downloader

import java.util.Locale

private val urlInText = Regex("""https?://\S+""")
private val urlOnly = Regex("""^https?://\S+$""")

fun extractUrl(text: String): String = urlInText.find(text)?.value ?: text.trim()

fun isUrl(text: String): Boolean = urlOnly.matches(text.trim())

fun formatDuration(totalSec: Long): String {
    if (totalSec <= 0) return ""
    val h = totalSec / 3600
    val m = totalSec % 3600 / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%d:%02d", m, s)
}

fun formatEta(sec: Long): String {
    if (sec < 0) return ""
    val h = sec / 3600
    val m = sec % 3600 / 60
    val s = sec % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%02d:%02d", m, s)
}
