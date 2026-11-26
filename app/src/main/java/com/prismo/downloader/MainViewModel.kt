package com.prismo.downloader

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    var url by mutableStateOf("")
        private set
    var meta by mutableStateOf<MetaState>(MetaState.Idle)
        private set
    var quality by mutableStateOf(Quality.BEST)
    var embedSubs by mutableStateOf(false)
    var sponsorBlock by mutableStateOf(false)

    val engine = DownloadHub.engine
    val queue = DownloadHub.items

    private var fetchJob: Job? = null
    private var fetchPid: String? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                DownloadHub.ensureInit(getApplication())
            } catch (_: Throwable) {
                // surfaced through DownloadHub.engine
            }
        }
    }

    /** Called when the user types, pastes, or shares a link. */
    fun onUrlChanged(text: String, immediate: Boolean = false) {
        url = text
        cancelFetch()
        val u = text.trim()
        if (!isUrl(u)) {
            meta = MetaState.Idle
            return
        }
        meta = MetaState.Loading
        fetchJob = viewModelScope.launch {
            try {
                if (!immediate) delay(700) // debounce typing
                val pid = "info-${System.nanoTime()}"
                fetchPid = pid
                val result = DownloadHub.fetchInfo(getApplication(), u, pid)
                meta = MetaState.Ready(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ensureActive()
                meta = MetaState.Error(cleanError(e))
            }
        }
    }

    fun clear() {
        cancelFetch()
        url = ""
        meta = MetaState.Idle
    }

    fun download(context: Context) {
        val u = url.trim()
        if (!isUrl(u)) return
        val m = (meta as? MetaState.Ready)?.meta
        DownloadHub.enqueue(context, u, m?.title ?: u, m?.thumbnail, quality, embedSubs, sponsorBlock)
        DownloadService.start(context)
        clear()
    }

    fun cancel(id: String) = DownloadHub.cancel(id)
    fun remove(id: String) = DownloadHub.remove(id)
    fun clearFinished() = DownloadHub.clearFinished()

    private fun cancelFetch() {
        fetchPid?.let { DownloadHub.cancelProcess(it) }
        fetchJob?.cancel()
        fetchPid = null
    }

    private fun cleanError(e: Throwable): String =
        e.message.orEmpty().lineSequence().lastOrNull { it.isNotBlank() }
            ?.removePrefix("ERROR: ")?.take(200) ?: "Couldn't read that link"
}
