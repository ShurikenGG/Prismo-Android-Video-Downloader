package com.prismo.downloader

enum class Quality(val label: String, val maxHeight: Int, val audioFormat: String? = null) {
    BEST("Best (up to 4K)", 2160),
    P2160("2160p · 4K", 2160),
    P1440("1440p · 2K", 1440),
    P1080("1080p", 1080),
    P720("720p", 720),
    AUDIO_M4A("Audio · M4A", 0, "m4a"),
    AUDIO_MP3("Audio · MP3", 0, "mp3");

    val audioOnly: Boolean get() = audioFormat != null
}

data class VideoMeta(
    val title: String,
    val channel: String,
    val durationSec: Long,
    val thumbnail: String?,
    val maxHeight: Int
)

enum class Status { QUEUED, DOWNLOADING, PROCESSING, SAVING, COMPLETED, FAILED, CANCELLED }

data class DownloadItem(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String?,
    val quality: Quality,
    val embedSubs: Boolean,
    val sponsorBlock: Boolean,
    val status: Status = Status.QUEUED,
    val progress: Float = 0f,
    val speed: String = "",
    val etaSeconds: Long = -1,
    val message: String = "",
    val fileName: String? = null,
    val fileUri: String? = null,
    val mimeType: String? = null
) {
    val isActive: Boolean
        get() = status == Status.QUEUED || status == Status.DOWNLOADING ||
            status == Status.PROCESSING || status == Status.SAVING
}

sealed interface EngineState {
    object Loading : EngineState
    object Ready : EngineState
    class Failed(val message: String) : EngineState
}

sealed interface MetaState {
    object Idle : MetaState
    object Loading : MetaState
    class Ready(val meta: VideoMeta) : MetaState
    class Error(val message: String) : MetaState
}
