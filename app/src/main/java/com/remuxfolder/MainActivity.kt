package com.remuxfolder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RemuxFolderTheme {
                RemuxFolderScreen()
            }
        }
    }
}

@Composable
private fun RemuxFolderScreen() {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("remux_folder", Context.MODE_PRIVATE)
    }
    val workManager = remember(context) { WorkManager.getInstance(context) }
    val licenseText = remember(context) {
        val notices = runCatching {
            context.assets.open("THIRD-PARTY-NOTICES.txt").bufferedReader().use { it.readText() }
        }.getOrNull()
        context.getString(R.string.license_summary) +
            if (notices.isNullOrBlank()) "" else "\n\n$notices"
    }

    var selectedTreeUri by remember {
        mutableStateOf(preferences.getString(KEY_LAST_TREE_URI, null))
    }
    var recentTreeUris by remember {
        mutableStateOf(
            preferences.getStringSet(KEY_RECENT_TREE_URIS, emptySet()).orEmpty().toList()
                .ifEmpty { listOfNotNull(preferences.getString(KEY_LAST_TREE_URI, null)) },
        )
    }
    var activeWorkId by remember {
        mutableStateOf(preferences.getString(KEY_LAST_WORK_ID, null))
    }
    var selectedContainerId by rememberSaveable {
        mutableStateOf(preferences.getString(KEY_LAST_CONTAINER, OutputContainer.MP4.id) ?: OutputContainer.MP4.id)
    }
    var videoCount by remember { mutableIntStateOf(0) }
    var totalVideoBytes by remember { mutableStateOf(0L) }
    var internalFreeBytes by remember { mutableStateOf(0L) }
    var scannedVideos by remember { mutableStateOf<List<SourceVideo>>(emptyList()) }
    var selectedFileUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var filePickerVisible by remember { mutableStateOf(false) }
    var allowDropAllAudio by remember {
        mutableStateOf(preferences.getBoolean(KEY_ALLOW_DROP_AUDIO, false))
    }
    var showDiagnostics by remember { mutableStateOf(false) }
    var diagnosticReport by remember { mutableStateOf("") }
    var diagnosticRunning by remember { mutableStateOf(false) }
    var safDiagnostic by remember { mutableStateOf("") }
    var fullResults by remember { mutableStateOf<List<RemuxFileResult>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()
    var isScanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var enqueueMessage by remember { mutableStateOf<String?>(null) }
    var showLicenses by remember { mutableStateOf(false) }
    var pendingPreflightOnly by remember { mutableStateOf(true) }
    var pendingRetryOnly by remember { mutableStateOf(false) }

    val selectedFolderName = remember(selectedTreeUri) {
        selectedTreeUri?.let { uriText ->
            runCatching {
                DocumentFile.fromTreeUri(context, Uri.parse(uriText))?.name
            }.getOrNull()
        }
    }
    val selectedContainer = OutputContainer.fromId(selectedContainerId)
    val workInfoFlow = remember(activeWorkId, workManager) {
        val parsedId = runCatching { activeWorkId?.let(UUID::fromString) }.getOrNull()
        if (parsedId == null) flowOf(null) else workManager.getWorkInfoByIdFlow(parsedId)
    }
    val workInfo by workInfoFlow.collectAsState(initial = null)
    val isWorking = workInfo?.state == WorkInfo.State.RUNNING ||
        workInfo?.state == WorkInfo.State.ENQUEUED ||
        workInfo?.state == WorkInfo.State.BLOCKED
    val preflightReady = workInfo?.state == WorkInfo.State.SUCCEEDED &&
        workInfo?.outputData?.getBoolean(RemuxWorker.KEY_IS_PREFLIGHT, false) == true
    val remuxableCount = (workInfo?.outputData?.getInt(RemuxWorker.KEY_READY_COUNT, 0) ?: 0) +
        (workInfo?.outputData?.getInt(RemuxWorker.KEY_PARTIAL_COUNT, 0) ?: 0)

    LaunchedEffect(selectedTreeUri) {
        val uriText = selectedTreeUri
        if (uriText == null) {
            videoCount = 0
            totalVideoBytes = 0L
            scannedVideos = emptyList()
            selectedFileUris = emptySet()
            scanError = null
        } else {
            isScanning = true
            scanError = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val scan = VideoScanner.scanDetailed(context, Uri.parse(uriText))
                    scan to StatFs(context.filesDir.absolutePath).availableBytes
                }
            }
            result.onSuccess {
                scannedVideos = it.first.videos
                videoCount = it.first.count
                totalVideoBytes = it.first.totalBytes
                internalFreeBytes = it.second
                selectedFileUris = it.first.videos.map { video -> video.uri.toString() }.toSet()
            }
                .onFailure {
                    videoCount = 0
                    scannedVideos = emptyList()
                    selectedFileUris = emptySet()
                    scanError = context.getString(R.string.scan_error)
                }
            isScanning = false
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            selectedTreeUri = uri.toString()
            fullResults = emptyList()
            preferences.edit().putString(KEY_LAST_TREE_URI, uri.toString()).apply()
            recentTreeUris = (listOf(uri.toString()) + recentTreeUris.filterNot { it == uri.toString() }).take(MAX_RECENT_FOLDERS)
            preferences.edit().putStringSet(KEY_RECENT_TREE_URIS, recentTreeUris.toSet()).apply()
            activeWorkId = null
            preferences.edit().remove(KEY_LAST_WORK_ID).apply()
            enqueueMessage = null
        }
    }

    fun enqueueRemux(preflightOnly: Boolean, retryOnly: Boolean = false) {
        val uriText = selectedTreeUri ?: return
        val workId = UUID.randomUUID()
        val resultKey = RemuxResultStore.key(uriText, selectedContainer.id)
        runCatching { RemuxResultStore.writeSelection(context, resultKey, selectedFileUris) }
        val request = OneTimeWorkRequestBuilder<RemuxWorker>()
            .setId(workId)
            .setInputData(
                workDataOf(
                    RemuxWorker.KEY_TREE_URI to uriText,
                    RemuxWorker.KEY_CONTAINER to selectedContainer.id,
                    RemuxWorker.KEY_PREFLIGHT to preflightOnly,
                    RemuxWorker.KEY_ALLOW_DROP_ALL_AUDIO to allowDropAllAudio,
                    RemuxWorker.KEY_RETRY_ONLY_FAILED to retryOnly,
                ),
            )
            .addTag(RemuxWorker.TAG)
            .build()
        activeWorkId = workId.toString()
        preferences.edit().putString(KEY_LAST_WORK_ID, activeWorkId).apply()
        runCatching {
            fullResults = emptyList()
            workManager.enqueueUniqueWork(
                RemuxWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }.onFailure {
            activeWorkId = null
            preferences.edit().remove(KEY_LAST_WORK_ID).apply()
            enqueueMessage = context.getString(R.string.work_start_error)
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            enqueueMessage = context.getString(R.string.notification_permission_note)
        }
        enqueueRemux(preflightOnly = pendingPreflightOnly, retryOnly = pendingRetryOnly)
    }

    fun beginRemux(preflightOnly: Boolean, retryOnly: Boolean = false) {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingPreflightOnly = preflightOnly
            pendingRetryOnly = retryOnly
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        else enqueueRemux(preflightOnly, retryOnly)
    }

    LaunchedEffect(workInfo?.state, selectedTreeUri, selectedContainerId) {
        val uriText = selectedTreeUri
        val finalStates = setOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)
        if (uriText != null && workInfo?.state?.let(finalStates::contains) == true) {
            val key = RemuxResultStore.key(uriText, selectedContainer.id)
            fullResults = withContext(Dispatchers.IO) { RemuxResultStore.read(context, key) }
        }
    }

    LaunchedEffect(showDiagnostics) {
        if (showDiagnostics) {
            diagnosticRunning = true
            diagnosticReport = withContext(Dispatchers.IO) { runCatching { FfmpegDiagnostics.collect() }.getOrElse { it.toString() } }
            diagnosticRunning = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Header()

            FolderCard(
                folderName = selectedFolderName,
                videoCount = videoCount,
                selectedCount = selectedFileUris.size,
                totalSize = Formatter.formatFileSize(context, totalVideoBytes),
                largestFileSize = Formatter.formatFileSize(context, scannedVideos.maxOfOrNull(SourceVideo::sizeBytes) ?: 0L),
                internalFreeSize = Formatter.formatFileSize(context, internalFreeBytes),
                isScanning = isScanning,
                scanError = scanError,
                selectionEnabled = !isWorking,
                onSelectFolder = { folderPicker.launch(null) },
                onShowPreview = { filePickerVisible = true },
            )
            RecentFoldersMenu(
                uris = recentTreeUris,
                selectionEnabled = !isWorking,
                onSelect = { uriText ->
                    selectedTreeUri = uriText
                    fullResults = emptyList()
                    preferences.edit().putString(KEY_LAST_TREE_URI, uriText).remove(KEY_LAST_WORK_ID).apply()
                    activeWorkId = null
                    enqueueMessage = null
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = allowDropAllAudio,
                    onCheckedChange = {
                        allowDropAllAudio = it
                        preferences.edit().putBoolean(KEY_ALLOW_DROP_AUDIO, it).apply()
                        activeWorkId = null
                        preferences.edit().remove(KEY_LAST_WORK_ID).apply()
                        fullResults = emptyList()
                    },
                    enabled = !isWorking,
                )
                Text(
                    stringResource(R.string.allow_drop_all_audio),
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            ContainerCard(
                selectedContainer = selectedContainer,
                selectionEnabled = !isWorking,
                menuExpanded = menuExpanded,
                onMenuExpandedChange = { menuExpanded = it },
                onContainerSelected = {
                    selectedContainerId = it.id
                    fullResults = emptyList()
                    preferences.edit().putString(KEY_LAST_CONTAINER, it.id).remove(KEY_LAST_WORK_ID).apply()
                    activeWorkId = null
                    menuExpanded = false
                },
            )

            InfoCard(onLicenses = { showLicenses = true })
            if (BuildConfig.DEBUG) {
                OutlinedButton(onClick = { showDiagnostics = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.diagnostic_button))
                }
            }
            if (showLicenses) {
                AlertDialog(
                    onDismissRequest = { showLicenses = false },
                    title = { Text(stringResource(R.string.license_title)) },
                    text = {
                        Text(
                            text = licenseText,
                            modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showLicenses = false }) {
                            Text(stringResource(R.string.license_close))
                        }
                    },
                )
            }

            Button(
                onClick = { beginRemux(preflightOnly = !preflightReady) },
                enabled = selectedTreeUri != null &&
                    (if (preflightReady) remuxableCount > 0 else selectedFileUris.isNotEmpty()) &&
                    !isScanning && !isWorking,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Mint,
                    contentColor = Ink,
                    disabledContainerColor = Color(0xFF29333A),
                    disabledContentColor = Color(0xFF7C8A89),
                ),
            ) {
                if (isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(19.dp),
                        strokeWidth = 2.dp,
                        color = Ink,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.button_remuxing), fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = when {
                            isScanning -> context.getString(R.string.button_scanning)
                            preflightReady -> context.getString(R.string.button_continue_count, remuxableCount)
                            selectedFileUris.isNotEmpty() -> context.getString(R.string.button_analyze_count, selectedFileUris.size)
                            else -> context.getString(R.string.button_select_folder)
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            enqueueMessage?.let { StatusMessage(it, isError = false) }
            WorkStatusCard(workInfo) { workId ->
                workManager.cancelWorkById(workId)
            }
            if (fullResults.isNotEmpty()) {
                FullResultsCard(
                    results = fullResults,
                    onShare = {
                        val key = RemuxResultStore.key(selectedTreeUri.orEmpty(), selectedContainer.id)
                        val exported = RemuxResultStore.exportFile(context, key, fullResults)
                        val exportedUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exported)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, exportedUri)
                            clipData = android.content.ClipData.newUri(context.contentResolver, "Remux Folder log", exportedUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.result_share_log)))
                    },
                    onRetry = { beginRemux(preflightOnly = false, retryOnly = true) },
                )
            }
            if (filePickerVisible) {
                FileSelectionDialog(
                    videos = scannedVideos,
                    selectedUris = selectedFileUris,
                    onDismiss = { filePickerVisible = false },
                    onToggle = { uri, selected ->
                        selectedFileUris = if (selected) selectedFileUris + uri else selectedFileUris - uri
                        activeWorkId = null
                        preferences.edit().remove(KEY_LAST_WORK_ID).apply()
                        fullResults = emptyList()
                    },
                    onSelectAll = {
                        selectedFileUris = scannedVideos.map { it.uri.toString() }.toSet()
                        activeWorkId = null
                        preferences.edit().remove(KEY_LAST_WORK_ID).apply()
                        fullResults = emptyList()
                    },
                    onSelectNone = {
                        selectedFileUris = emptySet()
                        activeWorkId = null
                        preferences.edit().remove(KEY_LAST_WORK_ID).apply()
                        fullResults = emptyList()
                    },
                )
            }
            if (showDiagnostics) {
                DiagnosticDialog(
                    report = diagnosticReport,
                    running = diagnosticRunning,
                    safResult = safDiagnostic,
                    hasFolder = selectedTreeUri != null,
                    onCopy = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Remux Folder diagnostic", diagnosticReport + "\n" + safDiagnostic))
                    },
                    onTestSaf = {
                        coroutineScope.launch {
                            safDiagnostic = context.getString(R.string.diagnostic_running)
                            val uri = selectedTreeUri?.let(Uri::parse)
                            safDiagnostic = if (uri == null) context.getString(R.string.scan_error) else withContext(Dispatchers.IO) {
                                runCatching { runSafDiagnostic(context, uri) }.getOrElse { it.toString() }
                            }
                        }
                    },
                    onDismiss = { showDiagnostics = false },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Surface(
            shape = RoundedCornerShape(50),
            color = Color(0xFF1C2D2D),
        ) {
            Text(
                text = stringResource(R.string.video_utility),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                color = Mint,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.3.sp,
            )
        }
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = Color(0xFFF3F5F4),
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text = stringResource(R.string.header_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = Muted,
        )
    }
}

@Composable
private fun FolderCard(
    folderName: String?,
    videoCount: Int,
    selectedCount: Int,
    totalSize: String,
    largestFileSize: String,
    internalFreeSize: String,
    isScanning: Boolean,
    scanError: String?,
    selectionEnabled: Boolean,
    onSelectFolder: () -> Unit,
    onShowPreview: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = BorderStroke(1.dp, Color(0xFF2B3342)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(15.dp),
                    color = Color(0xFF253735),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.FolderOpen,
                            contentDescription = null,
                            tint = Mint,
                            modifier = Modifier.size(25.dp),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folderName ?: stringResource(R.string.folder_choose),
                        color = Color(0xFFF4F6F6),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.folder_all_subfolders),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF141A25),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(17.dp),
                            strokeWidth = 2.dp,
                            color = Mint,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.folder_searching), color = Muted)
                    } else if (scanError != null) {
                        Text(scanError, color = Color(0xFFFFA39A), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Icon(
                            Icons.Filled.Movie,
                            contentDescription = null,
                            tint = Mint,
                            modifier = Modifier.size(19.dp),
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            text = pluralStringResource(R.plurals.folder_video_count, videoCount, videoCount),
                            color = Color(0xFFE0E7E5),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onSelectFolder,
                enabled = selectionEnabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFF49565C)),
            ) {
                Text(
                    text = stringResource(
                        if (folderName == null) R.string.folder_select else R.string.folder_change,
                    ),
                    color = Color(0xFFE2EAE7),
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (videoCount > 0 && !isScanning && scanError == null) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.size_total, totalSize), color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.size_required, largestFileSize), color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.size_free_internal, internalFreeSize), color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = onShowPreview,
                    enabled = selectionEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFF49565C)),
                ) {
                    Text(stringResource(R.string.preview_select_files, selectedCount, videoCount), color = Color(0xFFE2EAE7))
                }
            }
        }
    }
}

@Composable
private fun ContainerCard(
    selectedContainer: OutputContainer,
    selectionEnabled: Boolean,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onContainerSelected: (OutputContainer) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Text(
            text = stringResource(R.string.container_output),
            color = Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
        )
        Box {
            OutlinedButton(
                onClick = { onMenuExpandedChange(true) },
                enabled = selectionEnabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF343D4B)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = selectedContainer.label,
                            color = Color(0xFFF4F6F6),
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                        )
                        Text(
                            text = "." + selectedContainer.extension,
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("⌄", color = Mint, fontSize = 23.sp)
                }
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { onMenuExpandedChange(false) },
                modifier = Modifier.fillMaxWidth(0.86f),
            ) {
                OutputContainer.entries.forEach { container ->
                    DropdownMenuItem(
                        text = {
                            Text(container.label + "  (." + container.extension + ")")
                        },
                        onClick = { onContainerSelected(container) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentFoldersMenu(
    uris: List<String>,
    selectionEnabled: Boolean,
    onSelect: (String) -> Unit,
) {
    if (uris.isEmpty()) return
    val context = LocalContext.current
    val names = remember(uris) {
        uris.associateWith { value ->
            runCatching { DocumentFile.fromTreeUri(context, Uri.parse(value))?.name }.getOrNull() ?: value
        }
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = selectionEnabled) {
            Text(stringResource(R.string.recent_folders), color = Mint)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            uris.forEach { uri ->
                DropdownMenuItem(
                    text = { Text(names[uri].orEmpty()) },
                    onClick = { expanded = false; onSelect(uri) },
                )
            }
        }
    }
}

@Composable
private fun InfoCard(onLicenses: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2428)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                color = Color(0xFF28443C),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Mint,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.lossless_heading),
                    color = Color(0xFFE9F0ED),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.lossless_body),
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onLicenses, modifier = Modifier.align(Alignment.Start)) {
                    Text(stringResource(R.string.license_button), color = Mint)
                }
            }
        }
    }
}

@Composable
private fun WorkStatusCard(workInfo: WorkInfo?, onCancel: (UUID) -> Unit) {
    if (workInfo == null) return
    val progress = workInfo.progress
    val total = progress.getInt(RemuxWorker.KEY_TOTAL, 0)
    val completed = progress.getInt(RemuxWorker.KEY_COMPLETED, 0)
    val succeeded = progress.getInt(RemuxWorker.KEY_SUCCEEDED, 0)
    val failed = progress.getInt(RemuxWorker.KEY_FAILED, 0)
    val skipped = progress.getInt(RemuxWorker.KEY_SKIPPED, 0)
    val omittedTracks = progress.getInt(RemuxWorker.KEY_OMITTED_TRACKS, 0)
    val currentName = progress.getString(RemuxWorker.KEY_CURRENT_NAME)
    val stage = progress.getString(RemuxWorker.KEY_STAGE)
    val filePercent = progress.getInt(RemuxWorker.KEY_FILE_PERCENT, 0)
    val speed = progress.getString(RemuxWorker.KEY_SPEED).orEmpty().ifBlank { stringResource(R.string.speed_unknown) }
    val etaSeconds = progress.getLong(RemuxWorker.KEY_ETA_SECONDS, -1L)
    val eta = if (etaSeconds >= 0L) stringResource(R.string.eta_seconds, etaSeconds) else stringResource(R.string.eta_unknown)

    when (workInfo.state) {
        WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
            Card(
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(17.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = when {
                            total == 0 -> stringResource(R.string.progress_preparing)
                            stage == "analizando" -> stringResource(R.string.progress_analyzing)
                            stage == "remuxeando" -> stringResource(R.string.progress_remuxing)
                            else -> stringResource(R.string.progress_processing)
                        },
                        color = Color(0xFFF1F4F3),
                        fontWeight = FontWeight.Bold,
                    )
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = completed.toFloat() / total.toFloat(),
                            modifier = Modifier.fillMaxWidth(),
                            color = Mint,
                            trackColor = Color(0xFF303944),
                        )
                        Text(
                            text = stringResource(
                                R.string.progress_counts,
                                completed,
                                total,
                                succeeded,
                                failed,
                                skipped,
                            ),
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (omittedTracks > 0) {
                        Text(
                            text = stringResource(R.string.progress_omitted_tracks, omittedTracks),
                            color = Color(0xFFFFD28A),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    currentName?.let {
                        Text(
                            text = it,
                            color = Color(0xFFC7D2CF),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (stage == "remuxeando") {
                        LinearProgressIndicator(
                            progress = filePercent / 100f,
                            modifier = Modifier.fillMaxWidth(),
                            color = Mint,
                            trackColor = Color(0xFF303944),
                        )
                        Text(
                            stringResource(R.string.progress_file_percent, filePercent, speed, eta),
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(
                        onClick = { onCancel(workInfo.id) },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.action_cancel), color = Color(0xFFFFB3A9))
                    }
                }
            }
        }

        WorkInfo.State.SUCCEEDED -> {
            val finalTotal = workInfo.outputData.getInt(RemuxWorker.KEY_TOTAL, 0)
            val isPreflight = workInfo.outputData.getBoolean(RemuxWorker.KEY_IS_PREFLIGHT, false)
            val readyCount = workInfo.outputData.getInt(RemuxWorker.KEY_READY_COUNT, 0)
            val partialCount = workInfo.outputData.getInt(RemuxWorker.KEY_PARTIAL_COUNT, 0)
            val preflightDetails = workInfo.outputData.getStringArray(RemuxWorker.KEY_PREFLIGHT_DETAILS)
                .orEmpty()
            val preflightDetailCount = workInfo.outputData.getInt(RemuxWorker.KEY_PREFLIGHT_DETAIL_COUNT, 0)
            val finalSucceeded = workInfo.outputData.getInt(RemuxWorker.KEY_SUCCEEDED, 0)
            val finalFailed = workInfo.outputData.getInt(RemuxWorker.KEY_FAILED, 0)
            val finalSkipped = workInfo.outputData.getInt(RemuxWorker.KEY_SKIPPED, 0)
            val finalOmittedTracks = workInfo.outputData.getInt(RemuxWorker.KEY_OMITTED_TRACKS, 0)
            val failures = workInfo.outputData.getStringArray(RemuxWorker.KEY_FAILURE_DETAILS)
                .orEmpty()
            val warnings = workInfo.outputData.getStringArray(RemuxWorker.KEY_WARNING_DETAILS)
                .orEmpty()
            if (isPreflight) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (finalTotal == 0) {
                        StatusMessage(stringResource(R.string.result_no_videos), isError = false)
                    } else {
                        StatusMessage(
                            stringResource(
                                R.string.preflight_summary,
                                readyCount,
                                partialCount,
                                finalFailed,
                                finalSkipped,
                            ),
                            isError = readyCount + partialCount == 0,
                        )
                        if (partialCount > 0) {
                            Text(
                                text = stringResource(
                                    R.string.preflight_partial_warning,
                                    partialCount,
                                    finalOmittedTracks,
                                ),
                                color = Color(0xFFFFD28A),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (readyCount + partialCount == 0) {
                            Text(
                                text = stringResource(R.string.preflight_none),
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (preflightDetails.isNotEmpty()) {
                            Text(
                                text = preflightDetails.joinToString("\n"),
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (preflightDetailCount > preflightDetails.size) {
                            Text(
                                text = stringResource(R.string.details_truncated, preflightDetailCount),
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            } else {
                val message = when {
                    finalTotal == 0 -> stringResource(R.string.result_no_videos)
                    finalFailed == 0 && finalSkipped == 0 -> stringResource(R.string.result_all_done, finalSucceeded)
                    else -> stringResource(R.string.result_summary, finalSucceeded, finalFailed, finalSkipped)
                }
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    StatusMessage(message, isError = finalFailed > 0)
                    if (finalOmittedTracks > 0) {
                        Text(
                            text = stringResource(R.string.result_omitted_tracks, finalOmittedTracks),
                            color = Color(0xFFFFD28A),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (failures.isNotEmpty()) {
                        Text(
                            text = failures.joinToString("\n"),
                            color = Color(0xFFFFB3A9),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (warnings.isNotEmpty()) {
                        Text(
                            text = warnings.joinToString("\n"),
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val failureDetailCount = workInfo.outputData.getInt(RemuxWorker.KEY_FAILURE_DETAIL_COUNT, 0)
                    val warningDetailCount = workInfo.outputData.getInt(RemuxWorker.KEY_WARNING_DETAIL_COUNT, 0)
                    if (failureDetailCount > failures.size) {
                        Text(
                            text = stringResource(R.string.details_truncated, failureDetailCount),
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (warningDetailCount > warnings.size) {
                        Text(
                            text = stringResource(R.string.details_truncated, warningDetailCount),
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        WorkInfo.State.FAILED -> {
            val error = workInfo.outputData.getString(RemuxWorker.KEY_ERROR)
                ?: stringResource(R.string.result_start_error)
            StatusMessage(error, isError = true)
        }

        WorkInfo.State.CANCELLED -> StatusMessage(stringResource(R.string.result_cancelled), isError = false)
        else -> Unit
    }
}

@Composable
private fun StatusMessage(text: String, isError: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isError) Color(0xFF392423) else Color(0xFF1B3029),
        shape = RoundedCornerShape(15.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            color = if (isError) Color(0xFFFFB3A9) else Mint,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun FileSelectionDialog(
    videos: List<SourceVideo>,
    selectedUris: Set<String>,
    onDismiss: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preview_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onSelectAll) { Text(stringResource(R.string.preview_select_all)) }
                    TextButton(onClick = onSelectNone) { Text(stringResource(R.string.preview_select_none)) }
                }
                LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                    items(videos, key = { it.uri.toString() }) { video ->
                        val uri = video.uri.toString()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = uri in selectedUris, onCheckedChange = { onToggle(uri, it) })
                            Column(modifier = Modifier.weight(1f)) {
                                Text(video.name, color = Color(0xFFF3F5F4), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(video.relativeDirectories.joinToString("/"), Formatter.formatFileSize(context, video.sizeBytes))
                                        .filter(String::isNotBlank).joinToString(" · "),
                                    color = Muted,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.preview_done)) } },
    )
}

@Composable
private fun FullResultsCard(
    results: List<RemuxFileResult>,
    onShare: () -> Unit,
    onRetry: () -> Unit,
) {
    val failedCount = results.count(RemuxFileResult::isFailed)
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.result_complete_title, results.size), color = Color(0xFFF1F4F3), fontWeight = FontWeight.Bold)
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(results, key = { it.sourceUri }) { item ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        val status = when (item.status) {
                            "OK" -> stringResource(R.string.result_status_ok)
                            "ERROR" -> stringResource(R.string.result_status_error)
                            "IMPOSIBLE" -> stringResource(R.string.result_status_impossible)
                            "PENDIENTE" -> stringResource(R.string.result_status_pending)
                            else -> stringResource(R.string.result_status_skipped)
                        }
                        Text(stringResource(R.string.result_file_line, status, item.sourceName), color = Color(0xFFE7ECEA))
                        if (item.reason.isNotBlank()) Text(item.reason, color = Color(0xFFFFB3A9), style = MaterialTheme.typography.bodySmall)
                        if (item.warning.isNotBlank()) Text(item.warning, color = Color(0xFFFFD28A), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.result_share_log))
                }
                if (failedCount > 0) {
                    Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.result_retry_failed) + " ($failedCount)")
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticDialog(
    report: String,
    running: Boolean,
    safResult: String,
    hasFolder: Boolean,
    onCopy: () -> Unit,
    onTestSaf: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diagnostic_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.diagnostic_running))
                    }
                }
                Text(
                    text = report.ifBlank { stringResource(R.string.diagnostic_empty) },
                    modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(stringResource(R.string.diagnostic_saf_result), style = MaterialTheme.typography.bodySmall)
                if (safResult.isNotBlank()) Text(safResult, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.diagnostic_close)) } },
        dismissButton = {
            Row {
                if (hasFolder) TextButton(onClick = onTestSaf) { Text(stringResource(R.string.diagnostic_saf_run)) }
                TextButton(onClick = onCopy) { Text(stringResource(R.string.diagnostic_copy)) }
            }
        },
    )
}

private fun runSafDiagnostic(context: Context, treeUri: Uri): String {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("No se pudo abrir la carpeta.")
    val expected = ".remux-folder-check-${UUID.randomUUID()}.tmp"
    var cleanupName = expected
    var temporary = root.createFile("application/octet-stream", expected) ?: error("El proveedor no permitió crear un temporal.")
    try {
        val actual = temporary.name
        check(actual == expected) { "Nombre creado distinto: esperado $expected, creado $actual" }
        val sample = "Remux Folder SAF check".toByteArray(Charsets.UTF_8)
        context.contentResolver.openOutputStream(temporary.uri, "w")?.use { it.write(sample) }
            ?: error("No se pudo abrir el temporal para escribir.")
        val readBack = context.contentResolver.openInputStream(temporary.uri)?.use { it.readBytes() }
            ?: error("No se pudo volver a leer el temporal.")
        check(sample.contentEquals(readBack)) { "El texto de prueba no regresó igual." }
        val renamed = "$expected.renamed"
        check(temporary.renameTo(renamed)) { "El proveedor no permitió renombrar el temporal." }
        cleanupName = renamed
        val renamedFile = root.findFile(renamed) ?: error("El nombre final no apareció tras renombrar.")
        check(renamedFile.name == renamed) { "El nombre final cambió: ${renamedFile.name}" }
        temporary = renamedFile
        return "OK: lectura, escritura y nombre SAF funcionan. MP4/MOV usan un temporal local para faststart y después se copian al destino."
    } finally {
        temporary.delete()
        root.findFile(cleanupName)?.delete()
        if (cleanupName != expected) root.findFile(expected)?.delete()
    }
}

@Composable
private fun RemuxFolderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Mint,
            onPrimary = Ink,
            secondary = Color(0xFF8BC9B7),
            background = Color(0xFF11131B),
            surface = Panel,
            onSurface = Color(0xFFF3F5F4),
        ),
        content = content,
    )
}

private val Mint = Color(0xFF9BE5C8)
private val Ink = Color(0xFF13201D)
private val Muted = Color(0xFF9BA8AC)
private val Panel = Color(0xFF1B202C)
private const val KEY_LAST_TREE_URI = "last_tree_uri"
private const val KEY_LAST_WORK_ID = "last_work_id"
private const val KEY_LAST_CONTAINER = "last_container"
private const val KEY_ALLOW_DROP_AUDIO = "allow_drop_all_audio"
private const val KEY_RECENT_TREE_URIS = "recent_tree_uris"
private const val MAX_RECENT_FOLDERS = 5
