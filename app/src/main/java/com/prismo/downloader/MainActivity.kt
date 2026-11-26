package com.prismo.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import java.io.File

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) vm.download(this)
            else toast("Storage permission is needed to save into Downloads on this Android version")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            FourKTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        vm = vm,
                        onDownload = ::startDownload,
                        onOpen = ::openFile,
                        onShare = ::shareFile,
                        onDelete = ::deleteDownloadedFile
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank()) vm.onUrlChanged(extractUrl(text), immediate = true)
        }
    }

    private fun startDownload() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            vm.download(this)
        }
    }

    private fun openFile(item: DownloadItem) {
        val uri = item.fileUri?.let { Uri.parse(it) } ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, item.mimeType ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("No app found to open this file")
        }
    }

    private fun shareFile(item: DownloadItem) {
        val uri = item.fileUri?.let { Uri.parse(it) } ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType(item.mimeType ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(send, "Share file"))
        } catch (e: Exception) {
            toast("Couldn't share this file")
        }
    }

    /** Permanently deletes the saved file (MediaStore on Android 10+, plain file on older) and drops the card. */
    private fun deleteDownloadedFile(item: DownloadItem) {
        val uri = item.fileUri?.let { Uri.parse(it) }
        if (uri != null) {
            try {
                if (uri.scheme == "file") {
                    val f = File(uri.path.orEmpty())
                    if (f.exists() && !f.delete()) {
                        toast("Couldn't delete the file")
                        return
                    }
                    MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), null, null)
                } else {
                    var path: String? = null
                    contentResolver.query(
                        uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null
                    )?.use { c ->
                        if (c.moveToFirst()) path = c.getString(0)
                    }
                    contentResolver.delete(uri, null, null)
                    // Purge any stale Gallery/Files index entry for the deleted path.
                    path?.let { MediaScannerConnection.scanFile(this, arrayOf(it), null, null) }
                }
            } catch (e: SecurityException) {
                toast("Android blocked deleting this file. Delete it from the Files app instead.")
                return
            } catch (e: Exception) {
                toast("Couldn't delete the file: ${e.message}")
                return
            }
        }
        vm.remove(item.id)
        toast("File deleted")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

// ───────────────────────── Screen ─────────────────────────

@Composable
private fun MainScreen(
    vm: MainViewModel,
    onDownload: () -> Unit,
    onOpen: (DownloadItem) -> Unit,
    onShare: (DownloadItem) -> Unit,
    onDelete: (DownloadItem) -> Unit
) {
    val queue by vm.queue.collectAsState()
    val engine by vm.engine.collectAsState()
    val clipboard = LocalClipboardManager.current
    var pendingDelete by remember { mutableStateOf<DownloadItem?>(null) }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete file?") },
            text = {
                Text("Delete ${if (target.quality.audioOnly) "audio" else "video"} permanently from device storage?")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(target)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "header") { Header(engine) }

        item(key = "input") {
            UrlInput(
                url = vm.url,
                onChange = { vm.onUrlChanged(it) },
                onPaste = {
                    val text = clipboard.getText()?.text
                    if (!text.isNullOrBlank()) vm.onUrlChanged(extractUrl(text), immediate = true)
                },
                onClear = { vm.clear() },
                onGo = { vm.onUrlChanged(vm.url, immediate = true) }
            )
        }

        item(key = "meta") { MetaSection(vm.meta) }

        item(key = "options") {
            OptionsCard(
                quality = vm.quality,
                onQuality = { vm.quality = it },
                embedSubs = vm.embedSubs,
                onEmbedSubs = { vm.embedSubs = it },
                sponsorBlock = vm.sponsorBlock,
                onSponsorBlock = { vm.sponsorBlock = it }
            )
        }

        item(key = "go") {
            val ready = engine is EngineState.Ready
            Button(
                onClick = onDownload,
                enabled = ready && isUrl(vm.url),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(if (ready) "Add to queue" else "Starting engine…")
            }
        }

        item(key = "queue_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Queue (${queue.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (queue.any { !it.isActive }) {
                    TextButton(onClick = { vm.clearFinished() }) { Text("Clear finished") }
                }
            }
        }

        if (queue.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Downloads you add will show up here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        items(queue, key = { it.id }) { item ->
            DownloadCard(
                item = item,
                onCancel = { vm.cancel(item.id) },
                onRemove = { vm.remove(item.id) },
                onOpen = { onOpen(item) },
                onShare = { onShare(item) },
                onDelete = { pendingDelete = item }
            )
        }
    }
}

@Composable
private fun Header(engine: EngineState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Prismo", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        val (text, color) = when (engine) {
            EngineState.Loading -> "Starting download engine…" to MaterialTheme.colorScheme.onSurfaceVariant
            EngineState.Ready -> "Engine ready" to MaterialTheme.colorScheme.primary
            is EngineState.Failed -> "Engine error: ${engine.message}" to MaterialTheme.colorScheme.error
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun UrlInput(
    url: String,
    onChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onGo: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = onChange,
            label = { Text("Video link") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onPaste) { Text("Paste") }
            OutlinedButton(onClick = onClear, enabled = url.isNotEmpty()) { Text("Clear") }
        }
    }
}

@Composable
private fun MetaSection(meta: MetaState) {
    when (meta) {
        MetaState.Idle -> {}
        MetaState.Loading -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Text("Fetching video info…")
            }
        }
        is MetaState.Ready -> MetaCard(meta.meta)
        is MetaState.Error -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Couldn't read this link: ${meta.message}",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun MetaCard(meta: VideoMeta) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Thumb(meta.thumbnail, Modifier.width(140.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    meta.title,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (meta.channel.isNotBlank()) {
                    Text(meta.channel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val details = buildList {
                    formatDuration(meta.durationSec).takeIf { it.isNotEmpty() }?.let { add(it) }
                    if (meta.maxHeight > 0) add("up to ${meta.maxHeight}p")
                }.joinToString(" · ")
                if (details.isNotEmpty()) {
                    Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun OptionsCard(
    quality: Quality,
    onQuality: (Quality) -> Unit,
    embedSubs: Boolean,
    onEmbedSubs: (Boolean) -> Unit,
    sponsorBlock: Boolean,
    onSponsorBlock: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Quality", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Quality.values().forEach { q ->
                    FilterChip(
                        selected = quality == q,
                        onClick = { onQuality(q) },
                        label = { Text(q.label) }
                    )
                }
            }
            SwitchRow(
                title = "Embed subtitles",
                subtitle = if (quality.audioOnly) "Video downloads only" else "English, when available",
                checked = embedSubs && !quality.audioOnly,
                enabled = !quality.audioOnly,
                onChange = onEmbedSubs
            )
            SwitchRow(
                title = "SponsorBlock",
                subtitle = "Skip sponsor, self-promo and interaction segments",
                checked = sponsorBlock,
                enabled = true,
                onChange = onSponsorBlock
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

// ───────────────────────── Queue item ─────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadCard(
    item: DownloadItem,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Thumb(item.thumbnail, Modifier.width(112.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        item.fileName ?: item.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${item.quality.label} · ${statusLabel(item)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when (item.status) {
                Status.QUEUED -> LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth())
                Status.DOWNLOADING -> {
                    LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                    val extra = buildList {
                        if (item.speed.isNotEmpty()) add(item.speed)
                        if (item.etaSeconds >= 0) add("ETA ${formatEta(item.etaSeconds)}")
                    }.joinToString(" · ")
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${(item.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Status.PROCESSING, Status.SAVING -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Status.FAILED -> Text(
                    item.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                Status.COMPLETED, Status.CANCELLED -> {}
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                when {
                    item.isActive && item.status != Status.SAVING -> OutlinedButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Cancel") }

                    item.status == Status.COMPLETED -> {
                        Button(onClick = onOpen) { Text("Open") }
                        FilledTonalButton(onClick = onShare) { Text("Share") }
                        TextButton(onClick = onRemove) { Text("Remove") }
                        IconButton(onClick = onDelete) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete file permanently",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    item.status == Status.FAILED || item.status == Status.CANCELLED ->
                        TextButton(onClick = onRemove) { Text("Remove") }
                }
            }
        }
    }
}

private fun statusLabel(i: DownloadItem): String = when (i.status) {
    Status.QUEUED -> "Waiting in queue"
    Status.DOWNLOADING -> i.message.ifEmpty { "Downloading" }
    Status.PROCESSING -> "Processing (${i.message.ifEmpty { "merging" }})…"
    Status.SAVING -> "Saving to Downloads…"
    Status.COMPLETED -> "Saved to Downloads/${MediaStorage.FOLDER}"
    Status.FAILED -> "Failed"
    Status.CANCELLED -> "Cancelled"
}

@Composable
private fun Thumb(url: String?, modifier: Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
