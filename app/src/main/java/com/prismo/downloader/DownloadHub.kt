package com.prismo.downloader

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Process-wide download engine + queue. Survives Activity recreation. */
object DownloadHub {
    private const val YT_EXTRACTOR_ARGS = "youtube:player_client=default,android_vr"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(2) // concurrent downloads
    private val initMutex = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private val _engine = MutableStateFlow<EngineState>(EngineState.Loading)
    val engine: StateFlow<EngineState> = _engine.asStateFlow()

    private val speedRegex = Regex("""at\s+(\S+/s)""")
    private val postTags = setOf(
        "Merger", "ExtractAudio", "ModifyChapters", "EmbedSubtitle",
        "VideoConvertor", "VideoRemuxer", "Metadata", "FixupM3u8", "FixupM4a", "FixupM4v"
    )
    private val ignoredExt = setOf(
        "part", "ytdl", "vtt", "srt", "ass", "json", "jpg", "jpeg", "png", "webp", "temp", "txt"
    )

    // ---------- engine ----------

    suspend fun ensureInit(ctx: Context) {
        if (_engine.value is EngineState.Ready) return
        initMutex.withLock {
            if (_engine.value is EngineState.Ready) return
            try {
                YoutubeDL.getInstance().init(ctx.applicationContext)
                FFmpeg.getInstance().init(ctx.applicationContext)
                _engine.value = EngineState.Ready
            } catch (e: Throwable) {
                _engine.value = EngineState.Failed(e.message ?: "Engine failed to start")
                throw e
            }
        }
    }

    // ---------- metadata ----------

    suspend fun fetchInfo(ctx: Context, url: String, processId: String): VideoMeta =
        withContext(Dispatchers.IO) {
            ensureInit(ctx)
            val request = YoutubeDLRequest(url).apply {
                addOption("--dump-single-json")
                addOption("--no-playlist")
                addOption("--skip-download")
                addOption("--extractor-args", YT_EXTRACTOR_ARGS)
                addOption("--no-warnings")
            }
            val out = YoutubeDL.getInstance().execute(request, processId).out
            parseMeta(out)
        }

    fun cancelProcess(processId: String) {
        try {
            YoutubeDL.getInstance().destroyProcessById(processId)
        } catch (_: Throwable) {
        }
    }

    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

    private fun parseMeta(raw: String): VideoMeta {
        val start = raw.indexOf('{')
        if (start < 0) throw IllegalStateException("No metadata returned")
        val j = JSONObject(raw.substring(start))
        var maxH = 0
        val formats = j.optJSONArray("formats")
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                if (f.str("vcodec") != "none") maxH = maxOf(maxH, f.optInt("height", 0))
            }
        }
        return VideoMeta(
            title = j.str("title").ifBlank { "Untitled" },
            channel = j.str("channel").ifBlank { j.str("uploader") },
            durationSec = j.optDouble("duration", 0.0).toLong(),
            thumbnail = j.str("thumbnail").takeIf { it.startsWith("http") },
            maxHeight = maxH
        )
    }

    // ---------- queue ----------

    fun enqueue(
        ctx: Context,
        url: String,
        title: String,
        thumbnail: String?,
        quality: Quality,
        embedSubs: Boolean,
        sponsorBlock: Boolean
    ): String {
        val app = ctx.applicationContext
        val id = UUID.randomUUID().toString().take(8)
        val item = DownloadItem(
            id = id, url = url, title = title, thumbnail = thumbnail,
            quality = quality, embedSubs = embedSubs, sponsorBlock = sponsorBlock
        )
        _items.update { listOf(item) + it }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            permits.withPermit { process(app, id) }
        }
        jobs[id] = job
        job.invokeOnCompletion { jobs.remove(id) }
        job.start()
        return id
    }

    fun cancel(id: String) {
        val item = find(id) ?: return
        if (!item.isActive || item.status == Status.SAVING) return
        update(id) { it.copy(status = Status.CANCELLED, message = "Cancelled", speed = "", etaSeconds = -1) }
        cancelProcess(id)
        jobs[id]?.cancel()
    }

    fun remove(id: String) {
        val item = find(id) ?: return
        if (item.isActive) return
        _items.update { list -> list.filter { it.id != id } }
    }

    fun clearFinished() {
        _items.update { list -> list.filter { it.isActive } }
    }

    private fun find(id: String): DownloadItem? = _items.value.firstOrNull { it.id == id }

    private fun update(id: String, f: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    // ---------- worker ----------

    private suspend fun process(ctx: Context, id: String) {
        val item = find(id) ?: return
        if (item.status == Status.CANCELLED) return
        val workDir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "work/$id").apply { mkdirs() }
        try {
            update(id) { it.copy(status = Status.DOWNLOADING, message = "Preparing…") }
            ensureInit(ctx)
            val request = buildRequest(item, workDir)
            YoutubeDL.getInstance().execute(request, id) { p, eta, line ->
                onProgress(id, p, eta, line)
            }
            if (find(id)?.status == Status.CANCELLED) return

            update(id) {
                it.copy(status = Status.SAVING, progress = 1f, speed = "", etaSeconds = -1, message = "")
            }
            val file = pickResult(workDir) ?: throw IllegalStateException("No output file was produced")
            val saved = MediaStorage.saveToDownloads(ctx, file)
            update(id) {
                it.copy(
                    status = Status.COMPLETED, progress = 1f, message = "",
                    fileName = saved.displayName, fileUri = saved.uri.toString(), mimeType = saved.mime
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (find(id)?.status != Status.CANCELLED) {
                val msg = e.message.orEmpty().lineSequence().lastOrNull { it.isNotBlank() }
                    ?.removePrefix("ERROR: ")?.take(220) ?: "Download failed"
                update(id) { it.copy(status = Status.FAILED, message = msg, speed = "", etaSeconds = -1) }
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun onProgress(id: String, p: Float, eta: Long, line: String) {
        if (find(id)?.status == Status.CANCELLED) return
        if (line.startsWith("[download]") && p >= 0f) {
            val speed = speedRegex.find(line)?.groupValues?.get(1).orEmpty()
            update(id) {
                it.copy(
                    status = Status.DOWNLOADING,
                    progress = (p / 100f).coerceIn(0f, 1f),
                    speed = speed.ifEmpty { it.speed },
                    etaSeconds = eta,
                    message = ""
                )
            }
        } else if (line.startsWith("[")) {
            val tag = line.substringBefore(']').removePrefix("[")
            if (tag in postTags) {
                update(id) {
                    it.copy(status = Status.PROCESSING, progress = 1f, speed = "", etaSeconds = -1, message = tag)
                }
            }
        }
    }

    private fun buildRequest(item: DownloadItem, dir: File): YoutubeDLRequest =
        YoutubeDLRequest(item.url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--windows-filenames")
            addOption("--trim-filenames", "120")
            addOption("--concurrent-fragments", "4")
            addOption("--extractor-args", youtube:player_client=default,android_vr)
            addOption("-o", "${dir.absolutePath}/%(title)s.%(ext)s")

            val q = item.quality
            if (q.audioOnly) {
                addOption("-f", if (q.audioFormat == "m4a") "ba[ext=m4a]/ba/b" else "ba/b")
                addOption("-x")
                addOption("--audio-format", q.audioFormat!!)
                addOption("--audio-quality", "0")
            } else {
                val cap = "[height<=${q.maxHeight}]"
                addOption("-f", "bv*$cap+ba/b$cap/b")
                addOption("--merge-output-format", "mp4/mkv")
                if (item.embedSubs) {
                    addOption("--write-subs")
                    addOption("--sub-langs", "en.*")
                    addOption("--embed-subs")
                }
            }
            if (item.sponsorBlock) {
                addOption("--sponsorblock-remove", "sponsor,selfpromo,interaction")
            }
        }

    private fun pickResult(dir: File): File? =
        dir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() !in ignoredExt }
            .maxByOrNull { it.length() }
}
