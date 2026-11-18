package com.remuxfolder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine

class RemuxWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val treeUri = inputData.getString(KEY_TREE_URI)?.let(Uri::parse)
            ?: return Result.failure(errorData(applicationContext.getString(R.string.worker_input_missing)))
        val container = OutputContainer.fromId(inputData.getString(KEY_CONTAINER))
        val allowDropAllAudio = inputData.getBoolean(KEY_ALLOW_DROP_ALL_AUDIO, false)
        val retryOnly = inputData.getBoolean(KEY_RETRY_ONLY_FAILED, false)
        val resultKey = RemuxResultStore.key(treeUri.toString(), container.id)
        var wakeLock: PowerManager.WakeLock? = null

        return try {
            createNotificationChannel()
            safeSetForeground(foregroundInfo(applicationContext.getString(R.string.worker_preparing), 0, 0))

            val root = DocumentFile.fromTreeUri(applicationContext, treeUri)
                ?: return Result.failure(errorData(applicationContext.getString(R.string.worker_folder_open_error)))
            val scan = withContext(Dispatchers.IO) { VideoScanner.scanDetailed(applicationContext, treeUri) }
            val selectedUris = RemuxResultStore.readSelection(applicationContext, resultKey)
            val failedUris = if (retryOnly) RemuxResultStore.read(applicationContext, resultKey)
                .filter(RemuxFileResult::isFailed).map(RemuxFileResult::sourceUri).toSet() else emptySet()
            val sources = scan.videos.filter {
                (selectedUris == null || it.uri.toString() in selectedUris) && (!retryOnly || it.uri.toString() in failedUris)
            }
            if (sources.isEmpty()) {
                if (!retryOnly) RemuxResultStore.replace(applicationContext, resultKey, emptyList())
                val emptyData = Data.Builder()
                    .putInt(KEY_TOTAL, 0)
                    .putInt(KEY_SUCCEEDED, 0)
                    .putInt(KEY_FAILED, 0)
                    .putInt(KEY_SKIPPED, 0)
                    .putInt(KEY_OMITTED_TRACKS, 0)
                    .putBoolean(KEY_IS_PREFLIGHT, inputData.getBoolean(KEY_PREFLIGHT, false))
                    .putString(KEY_RESULT_KEY, resultKey)
                    .build()
                setProgress(emptyData)
                postCompletionNotification(emptyData, isPreflight = inputData.getBoolean(KEY_PREFLIGHT, false))
                return Result.success(emptyData)
            }

            if (inputData.getBoolean(KEY_PREFLIGHT, false)) {
                val encoders = FfmpegDiagnostics.availableSubtitleEncoders()
                return runPreflight(sources, container, allowDropAllAudio, encoders, resultKey)
            }

            if (!retryOnly) RemuxResultStore.replace(applicationContext, resultKey, emptyList())

            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemuxFolder:batch").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            val encoders = FfmpegDiagnostics.availableSubtitleEncoders()
            val bitstreamFilters = FfmpegDiagnostics.availableBitstreamFilters()
            if (container == OutputContainer.MP4 || container == OutputContainer.MOV) {
                val largestInput = sources.maxOfOrNull(SourceVideo::sizeBytes) ?: 0L
                val cacheFree = StatFs(applicationContext.cacheDir.absolutePath).availableBytes
                if (largestInput > cacheFree) {
                    val reason = applicationContext.getString(
                        R.string.error_cache_space,
                        android.text.format.Formatter.formatFileSize(applicationContext, largestInput),
                    )
                    sources.forEach { source ->
                        RemuxResultStore.upsert(
                            applicationContext,
                            resultKey,
                            RemuxFileResult(source.uri.toString(), source.name, "ERROR", RemuxErrorCode.SIN_ESPACIO.name, reason),
                        )
                    }
                    throw IOException(reason)
                }
            }

            val outputFolderName = OutputFolderNaming.current(root.name)
            val legacyOutputFolderName = OutputFolderNaming.legacy(root.name)
            var outputRoot: DocumentFile? = null
            var succeeded = 0
            var failed = 0
            var skipped = 0
            var omittedTracks = 0
            val failureDetails = mutableListOf<String>()
            val warningDetails = mutableListOf<String>()
            val destinationNameMaps = mutableMapOf<String, MutableMap<String, DocumentFile>>()
            var stopForNoSpace = false
            var completedCount = 0
            val destinationDirectoryCache = mutableMapOf<String, DocumentFile>()

            for ((index, source) in sources.withIndex()) {
                if (stopForNoSpace) break
                var temporaryFile: DocumentFile? = null
                var localTemporary: File? = null
                var activePlan: RemuxPlan? = null
                var fileLog = ""
                try {
                    safeSetForeground(
                        foregroundInfo(
                            text = applicationContext.getString(
                                R.string.worker_analyzing,
                                index + 1,
                                sources.size,
                                source.name,
                            ),
                            total = sources.size,
                            completed = index,
                        ),
                    )
                    setProgress(
                        progressData(
                            total = sources.size,
                            completed = index,
                            succeeded = succeeded,
                            failed = failed,
                            skipped = skipped,
                            omittedTracks = omittedTracks,
                            currentName = source.name,
                            stage = "analizando",
                        ),
                    )

                    val inputPath = FFmpegKitConfig.getSafParameterForRead(applicationContext, source.uri)
                    val inputMedia = probe(inputPath)
                    val sourceExtension = source.name.substringAfterLast('.', "")
                        .lowercase(Locale.ROOT)
                    val plan = MediaRemuxPlanner.plan(
                        inputMedia,
                        sourceExtension,
                        container,
                        allowDropAllAudio,
                        encoders,
                    )
                    activePlan = plan
                    if (plan.disposition == PlanDisposition.SKIP_SAME_CONTAINER) {
                        skipped++
                        val record = RemuxFileResult(source.uri.toString(), source.name, "OMITIDO")
                        RemuxResultStore.upsert(applicationContext, resultKey, record)
                        continue
                    }
                    if (!plan.canRemux) {
                        failed++
                        val reason = formatRejection(plan, container)
                        failureDetails += "${source.name}: $reason"
                        val record = RemuxFileResult(source.uri.toString(), source.name, "IMPOSIBLE", plan.rejection?.name.orEmpty(), reason)
                        RemuxResultStore.upsert(applicationContext, resultKey, record)
                        continue
                    }

                    val outputDirectory = outputRoot
                        ?: getOrCreateOutputFolder(root, outputFolderName, legacyOutputFolderName).also {
                            outputRoot = it
                            cleanupOrphanPartials(it)
                        }
                    var relativePath = ""
                    val destinationDirectory = source.relativeDirectories.fold(outputDirectory) { parent, name ->
                        relativePath = if (relativePath.isEmpty()) name else "$relativePath/$name"
                        destinationDirectoryCache.getOrPut(relativePath) {
                            getOrCreateDirectory(parent, FileNamePolicy.safeSegment(name))
                        }
                    }
                    val preferredName = outputFileName(source.name, container.extension)
                    val names = destinationNameMaps.getOrPut(destinationDirectory.uri.toString()) {
                        destinationDirectory.listFiles().mapNotNull { file -> file.name?.let { it to file } }.toMap().toMutableMap()
                    }
                    val previousOutput = names[preferredName]
                    if (previousOutput != null && isReusableOutput(previousOutput, plan, inputMedia)) {
                        succeeded++
                        omittedTracks += omittedTrackCount(plan.warnings)
                        if (plan.warnings.isNotEmpty()) {
                            warningDetails += "${source.name}: ${plan.warnings.joinToString(" ", transform = ::formatWarning)}"
                        }
                        val record = RemuxFileResult(
                            source.uri.toString(), source.name, "OK", warning = formatWarnings(plan.warnings), outputName = preferredName,
                        )
                        RemuxResultStore.upsert(applicationContext, resultKey, record)
                        continue
                    }
                    val destinationName = uniqueFileName(
                        existingNames = names.keys,
                        sourceName = source.name,
                        extension = container.extension,
                    )
                    val partialName = "$destinationName.part"
                    temporaryFile = destinationDirectory.createFile("application/octet-stream", partialName)
                        ?: throw IOException(applicationContext.getString(R.string.worker_output_prepare_error, source.name))
                    val actualPartialName = temporaryFile?.name
                    if (actualPartialName != partialName) {
                        temporaryFile?.delete()
                        temporaryFile = null
                        throw IOException(applicationContext.getString(R.string.worker_name_mismatch, actualPartialName ?: "?", partialName))
                    }
                    names[partialName] = temporaryFile!!

                    safeSetForeground(
                        foregroundInfo(
                            text = applicationContext.getString(
                                R.string.worker_remuxing,
                                index + 1,
                                sources.size,
                                source.name,
                            ),
                            total = sources.size,
                            completed = index,
                        ),
                    )
                    setProgress(
                        progressData(
                            total = sources.size,
                            completed = index,
                            succeeded = succeeded,
                            failed = failed,
                            skipped = skipped,
                            omittedTracks = omittedTracks,
                            currentName = source.name,
                            stage = "remuxeando",
                        ),
                    )

                    val useSeekableLocalOutput = container == OutputContainer.MP4 || container == OutputContainer.MOV
                    val outputPath = if (useSeekableLocalOutput) {
                        File(applicationContext.cacheDir, "remux-${UUID.randomUUID()}.${container.extension}.part")
                            .also { localTemporary = it }.absolutePath
                    } else {
                        FFmpegKitConfig.getSafParameterForWrite(applicationContext, temporaryFile!!.uri)
                    }
                    var planForOutput = plan
                    var session = runFfmpeg(
                        buildArguments(planForOutput, sourceExtension, inputMedia, bitstreamFilters, inputPath, outputPath, container),
                    ) { time, speed ->
                        publishFileProgress(source.name, index, sources.size, succeeded, failed, skipped, omittedTracks, time, inputMedia.durationSeconds, speed)
                    }
                    var logs = session.allLogsAsString
                    fileLog = logs
                    if (!ReturnCode.isSuccess(session.returnCode)) {
                        val outputStreamIndex = OUTPUT_STREAM_INDEX_REGEX.find(logs)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        val inputStreamIndex = INPUT_STREAM_INDEX_REGEX.find(logs)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        val failedStream = outputStreamIndex?.let(planForOutput.streamsToCopy::getOrNull)
                            ?: inputStreamIndex?.let { inputIndex ->
                                planForOutput.streamsToCopy.firstOrNull { it.index == inputIndex }
                            }
                        val fallbackPlan = failedStream?.let { withoutStream(planForOutput, it.index, allowDropAllAudio) }
                        if (fallbackPlan != null) {
                            planForOutput = fallbackPlan
                            activePlan = planForOutput
                            localTemporary?.delete()
                            if (!useSeekableLocalOutput) {
                                temporaryFile?.delete()
                                temporaryFile = destinationDirectory.createFile("application/octet-stream", partialName)
                                    ?: throw IOException(applicationContext.getString(R.string.worker_output_prepare_error, source.name))
                                val retryPartialName = temporaryFile?.name
                                if (retryPartialName != partialName) {
                                    temporaryFile?.delete()
                                    temporaryFile = null
                                    throw IOException(applicationContext.getString(R.string.worker_name_mismatch, retryPartialName ?: "?", partialName))
                                }
                            }
                            val retryOutput = if (useSeekableLocalOutput) outputPath else
                                FFmpegKitConfig.getSafParameterForWrite(applicationContext, temporaryFile!!.uri)
                            session = runFfmpeg(
                                buildArguments(planForOutput, sourceExtension, inputMedia, bitstreamFilters, inputPath, retryOutput, container),
                            ) { time, speed ->
                                publishFileProgress(source.name, index, sources.size, succeeded, failed, skipped, omittedTracks, time, inputMedia.durationSeconds, speed)
                            }
                            logs = "${logs.takeLast(MAX_LOG_CHARS / 2)}\n--- reintento sin pista ${failedStream?.index ?: "?"} ---\n${session.allLogsAsString}"
                            fileLog = logs
                        }
                    }
                    if (!ReturnCode.isSuccess(session.returnCode)) {
                        throw FfmpegFailure(logs, session.failStackTrace.orEmpty())
                    }
                    activePlan = planForOutput
                    if (useSeekableLocalOutput) {
                        validateOutput(outputPath, planForOutput, inputMedia)
                        copyFileToDocument(File(outputPath), temporaryFile!!)
                    }
                    validateOutput(temporaryFile!!, planForOutput, inputMedia)

                    val publishedFile = publishValidatedFile(
                        directory = destinationDirectory,
                        partialFile = temporaryFile!!,
                        finalName = destinationName,
                        plan = planForOutput,
                        sourceMedia = inputMedia,
                    )
                    temporaryFile = null
                    names.remove(partialName)
                    publishedFile.name?.let { names[it] = publishedFile }
                    if (!publishedFile.exists()) {
                        throw IOException(applicationContext.getString(R.string.worker_final_missing))
                    }

                    succeeded++
                    omittedTracks += omittedTrackCount(planForOutput.warnings)
                    if (planForOutput.warnings.isNotEmpty()) {
                        warningDetails += "${source.name}: ${planForOutput.warnings.joinToString(" ", transform = ::formatWarning)}"
                    }
                    val thermalWarning = thermalWarning(powerManager)
                    val finalWarnings = listOf(formatWarnings(planForOutput.warnings), thermalWarning).filter(String::isNotBlank).joinToString("; ")
                    val record = RemuxFileResult(
                        source.uri.toString(), source.name, "OK", warning = finalWarnings, outputName = destinationName,
                    )
                    RemuxResultStore.upsert(applicationContext, resultKey, record)
                } catch (cancelled: CancellationException) {
                    temporaryFile?.delete()
                    throw cancelled
                } catch (exception: Exception) {
                    temporaryFile?.delete()
                    failed++
                    val failure = exception as? FfmpegFailure
                    val log = failure?.logs.orEmpty().ifBlank { fileLog }
                    val errorText = generateSequence<Throwable>(exception) { it.cause }
                        .mapNotNull { it.message }.joinToString("\n")
                    val errorCode = ErrorClassifier.classify("$log\n$errorText")
                    val reason = if (exception is FfmpegFailure || errorCode != RemuxErrorCode.DESCONOCIDO) {
                        formatError(errorCode, container, log.ifBlank { errorText })
                    } else exception.message ?: formatError(errorCode, container)
                    failureDetails += "${source.name}: $reason"
                    val warnings = formatWarnings(activePlan?.warnings.orEmpty())
                    val record = RemuxFileResult(
                        sourceUri = source.uri.toString(),
                        sourceName = source.name,
                        status = "ERROR",
                        reasonCode = errorCode.name,
                        reason = reason,
                        warning = warnings,
                        log = (log + "\n" + failure?.failStackTrace.orEmpty()).trim(),
                    )
                    RemuxResultStore.upsert(applicationContext, resultKey, record)
                    if (errorCode == RemuxErrorCode.SIN_ESPACIO) stopForNoSpace = true
                } finally {
                    localTemporary?.delete()
                    completedCount++
                }
            }

            if (stopForNoSpace) {
                val reason = applicationContext.getString(R.string.error_no_space)
                sources.drop(completedCount).forEach { source ->
                    failed++
                    failureDetails += "${source.name}: $reason"
                    RemuxResultStore.upsert(
                        applicationContext,
                        resultKey,
                        RemuxFileResult(source.uri.toString(), source.name, "ERROR", RemuxErrorCode.SIN_ESPACIO.name, reason),
                    )
                }
            }

            val resultData = Data.Builder()
                .putInt(KEY_TOTAL, sources.size)
                .putInt(KEY_SUCCEEDED, succeeded)
                .putInt(KEY_FAILED, failed)
                .putInt(KEY_SKIPPED, skipped)
                .putInt(KEY_OMITTED_TRACKS, omittedTracks)
                .putString(KEY_RESULT_KEY, resultKey)
                .putString(KEY_TREE_URI, treeUri.toString())
                .putString(KEY_CONTAINER, container.id)
                .putInt(KEY_FAILURE_DETAIL_COUNT, failureDetails.size)
                .putInt(KEY_WARNING_DETAIL_COUNT, warningDetails.size)
                .putStringArray(
                    KEY_FAILURE_DETAILS,
                    failureDetails.take(MAX_RESULT_DETAILS).map { it.take(MAX_RESULT_DETAIL_LENGTH) }.toTypedArray(),
                )
                .putStringArray(
                    KEY_WARNING_DETAILS,
                    warningDetails.take(MAX_RESULT_DETAILS).map { it.take(MAX_RESULT_DETAIL_LENGTH) }.toTypedArray(),
                )
                .build()
            setProgress(
                progressData(
                    total = sources.size,
                    completed = completedCount,
                    succeeded = succeeded,
                    failed = failed,
                    skipped = skipped,
                    omittedTracks = omittedTracks,
                    currentName = null,
                    stage = "terminado",
                ),
            )
            postCompletionNotification(resultData, isPreflight = false)
            Result.success(resultData)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            Result.failure(
                errorData(exception.message ?: applicationContext.getString(R.string.worker_unexpected_error)),
            )
        } finally {
            runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        }
    }

    private suspend fun runPreflight(
        sources: List<SourceVideo>,
        container: OutputContainer,
        allowDropAllAudio: Boolean,
        encoders: Set<String>,
        resultKey: String,
    ): Result {
        var ready = 0
        var partial = 0
        var incompatible = 0
        var unchanged = 0
        var omittedTracks = 0
        val details = mutableListOf<String>()
        RemuxResultStore.replace(applicationContext, resultKey, emptyList())
        sources.forEachIndexed { index, source ->
            safeSetForeground(
                foregroundInfo(
                    applicationContext.getString(
                        R.string.worker_analyzing,
                        index + 1,
                        sources.size,
                        source.name,
                    ),
                    sources.size,
                    index,
                ),
            )
            try {
                val inputPath = FFmpegKitConfig.getSafParameterForRead(applicationContext, source.uri)
                val media = probe(inputPath)
                val extension = source.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val plan = MediaRemuxPlanner.plan(media, extension, container, allowDropAllAudio, encoders)
                val preflightRecord = when (plan.disposition) {
                    PlanDisposition.REJECTED -> RemuxFileResult(
                        source.uri.toString(), source.name, "IMPOSIBLE", plan.rejection?.name.orEmpty(), formatRejection(plan, container),
                    )
                    PlanDisposition.SKIP_SAME_CONTAINER -> RemuxFileResult(source.uri.toString(), source.name, "OMITIDO")
                    else -> RemuxFileResult(
                        source.uri.toString(), source.name, "PENDIENTE", warning = formatWarnings(plan.warnings),
                    )
                }
                RemuxResultStore.upsert(applicationContext, resultKey, preflightRecord)
                when (plan.disposition) {
                    PlanDisposition.READY -> ready++
                    PlanDisposition.READY_WITH_WARNINGS -> {
                        partial++
                        omittedTracks += omittedTrackCount(plan.warnings)
                        details += "${source.name}: ${plan.warnings.joinToString(" ", transform = ::formatWarning)}"
                    }
                    PlanDisposition.SKIP_SAME_CONTAINER -> unchanged++
                    PlanDisposition.REJECTED -> {
                        incompatible++
                        details += "${source.name}: ${formatRejection(plan, container)}"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                incompatible++
                val errorText = generateSequence<Throwable>(exception) { it.cause }.mapNotNull { it.message }.joinToString("\n")
                val errorCode = ErrorClassifier.classify(errorText)
                val reason = if (errorCode == RemuxErrorCode.DESCONOCIDO) {
                    exception.message ?: applicationContext.getString(R.string.error_remux_generic)
                } else formatError(errorCode, container, errorText)
                details += "${source.name}: $reason"
                RemuxResultStore.upsert(
                    applicationContext,
                    resultKey,
                    RemuxFileResult(source.uri.toString(), source.name, "ERROR", errorCode.name, reason),
                )
            }
            setProgress(
                progressData(
                    total = sources.size,
                    completed = index + 1,
                    succeeded = ready + partial,
                    failed = incompatible,
                    skipped = unchanged,
                    omittedTracks = omittedTracks,
                    currentName = source.name,
                    stage = "analizando",
                ),
            )
        }
        val output = Data.Builder()
            .putInt(KEY_TOTAL, sources.size)
            .putInt(KEY_READY_COUNT, ready)
            .putInt(KEY_PARTIAL_COUNT, partial)
            .putInt(KEY_FAILED, incompatible)
            .putInt(KEY_SKIPPED, unchanged)
            .putInt(KEY_OMITTED_TRACKS, omittedTracks)
            .putBoolean(KEY_IS_PREFLIGHT, true)
            .putString(KEY_RESULT_KEY, resultKey)
            .putString(KEY_TREE_URI, inputData.getString(KEY_TREE_URI))
            .putString(KEY_CONTAINER, container.id)
            .putInt(KEY_PREFLIGHT_DETAIL_COUNT, details.size)
            .putStringArray(
                KEY_PREFLIGHT_DETAILS,
                details.take(MAX_RESULT_DETAILS).map { it.take(MAX_RESULT_DETAIL_LENGTH) }.toTypedArray(),
            )
            .build()
        postCompletionNotification(output, isPreflight = true)
        return Result.success(output)
    }

    private fun postCompletionNotification(data: Data, isPreflight: Boolean) {
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return
        val total = data.getInt(KEY_TOTAL, 0)
        val succeeded = data.getInt(KEY_SUCCEEDED, 0)
        val failed = data.getInt(KEY_FAILED, 0)
        val skipped = data.getInt(KEY_SKIPPED, 0)
        val partial = data.getInt(KEY_PARTIAL_COUNT, 0)
        val ready = data.getInt(KEY_READY_COUNT, 0)
        val text = when {
            total == 0 -> applicationContext.getString(R.string.notification_no_videos)
            isPreflight -> applicationContext.getString(
                R.string.notification_preflight_complete,
                ready + partial,
                partial,
                failed,
            )
            else -> applicationContext.getString(
                R.string.notification_complete,
                succeeded,
                failed,
                skipped,
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(COMPLETION_NOTIFICATION_ID, notification)
        }
    }

    private suspend fun probe(inputPath: String): ProbedMedia {
        val finished = CompletableDeferred<Unit>()
        val json = try {
            suspendCancellableCoroutine<String?> { continuation ->
                val session = FFprobeKit.executeWithArgumentsAsync(
                    arrayOf(
                        "-v", "error",
                        "-show_streams",
                        "-show_format",
                        "-of", "json",
                        inputPath,
                    ),
                ) { completedSession ->
                    finished.complete(Unit)
                    if (continuation.isActive) {
                        continuation.resume(
                            if (ReturnCode.isSuccess(completedSession.returnCode)) completedSession.output else null,
                        )
                    }
                }
                continuation.invokeOnCancellation {
                    FFmpegKit.cancel(session.sessionId)
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                withTimeoutOrNull(SESSION_CANCEL_WAIT_MS) { finished.await() }
            }
            throw cancelled
        } ?: throw IOException(applicationContext.getString(R.string.worker_ffprobe_error))

        return try {
            MediaProbeParser.parse(json)
        } catch (exception: Exception) {
            throw IOException(applicationContext.getString(R.string.worker_probe_parse_error), exception)
        }
    }

    private suspend fun runFfmpeg(
        arguments: Array<String>,
        onStatistics: (timeMillis: Long, speed: String) -> Unit,
    ): FFmpegSession {
        val finished = CompletableDeferred<Unit>()
        return try {
            suspendCancellableCoroutine { continuation ->
                val session = FFmpegKit.executeWithArgumentsAsync(
                    arguments,
                    { completedSession ->
                        finished.complete(Unit)
                        if (continuation.isActive) continuation.resume(completedSession)
                    },
                    null,
                    { statistics ->
                        onStatistics(statistics.time.toLong(), String.format(Locale.ROOT, "%.2fx", statistics.speed))
                    },
                )
                continuation.invokeOnCancellation {
                    FFmpegKit.cancel(session.sessionId)
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                withTimeoutOrNull(SESSION_CANCEL_WAIT_MS) { finished.await() }
            }
            throw cancelled
        }
    }

    private fun buildArguments(
        plan: RemuxPlan,
        sourceExtension: String,
        sourceMedia: ProbedMedia,
        bitstreamFilters: Set<String>,
        inputPath: String,
        outputPath: String,
        container: OutputContainer,
    ): Array<String> = buildList {
        addAll(listOf("-hide_banner", "-nostdin", "-ignore_unknown"))
        if (sourceExtension in TIMESTAMP_REPAIR_EXTENSIONS || sourceMedia.formatNames.any { it in TIMESTAMP_REPAIR_FORMATS }) {
            addAll(listOf("-fflags", "+genpts"))
        }
        addAll(listOf("-i", inputPath))
        addAll(plan.ffmpegMapArguments())
        add("-dn")
        addAll(listOf("-c", "copy"))
        addAll(plan.ffmpegSubtitleArguments())
        addAll(plan.ffmpegVideoTagArguments(container))
        addAll(bitstreamFilterArguments(plan, sourceMedia, container, bitstreamFilters))
        addAll(listOf("-map_metadata", "0"))
        if (container in CHAPTER_CONTAINERS) addAll(listOf("-map_chapters", "0"))
        addAll(listOf("-avoid_negative_ts", "make_zero"))
        if (container == OutputContainer.MP4 || container == OutputContainer.MOV) {
            addAll(listOf("-movflags", "+faststart"))
        }
        addAll(listOf("-f", container.muxer, "-y", outputPath))
    }.toTypedArray()

    private fun bitstreamFilterArguments(
        plan: RemuxPlan,
        source: ProbedMedia,
        target: OutputContainer,
        available: Set<String>,
    ): List<String> = buildList {
        val sourceAacElementary = "aac" in source.formatNames
        val quickTimeInput = source.formatNames.any { it in setOf("mov", "mp4") }
        var audioIndex = 0
        var videoIndex = 0
        plan.streamsToCopy.forEach { stream ->
            if (stream.type == MediaStreamType.AUDIO) {
                if (sourceAacElementary && stream.codec == "aac" && target in setOf(OutputContainer.MP4, OutputContainer.MOV, OutputContainer.MKV) &&
                    "aac_adtstoasc" in available
                ) addAll(listOf("-bsf:a:$audioIndex", "aac_adtstoasc"))
                audioIndex++
            } else if (stream.type == MediaStreamType.VIDEO) {
                val filter = when {
                    quickTimeInput && target == OutputContainer.TS && stream.codec == "h264" -> "h264_mp4toannexb"
                    quickTimeInput && target == OutputContainer.TS && stream.codec == "hevc" -> "hevc_mp4toannexb"
                    else -> null
                }
                if (filter != null && filter in available) addAll(listOf("-bsf:v:$videoIndex", filter))
                videoIndex++
            }
        }
    }

    private fun withoutStream(plan: RemuxPlan, streamIndex: Int, allowDropAllAudio: Boolean): RemuxPlan? {
        val removed = plan.streamsToCopy.firstOrNull { it.index == streamIndex } ?: return null
        val kept = plan.streamsToCopy.filterNot { it.index == streamIndex }
        if (kept.none { it.type == MediaStreamType.VIDEO && !it.attachedPicture }) return null
        if (plan.streamsToCopy.any { it.type == MediaStreamType.AUDIO } &&
            kept.none { it.type == MediaStreamType.AUDIO } && !allowDropAllAudio
        ) return null

        var oldSubtitleIndex = 0
        var newSubtitleIndex = 0
        val subtitleEncoders = mutableMapOf<Int, String>()
        plan.streamsToCopy.forEach { stream ->
            if (stream.type == MediaStreamType.SUBTITLE) {
                val encoder = plan.subtitleEncodersByOutputIndex[oldSubtitleIndex]
                if (stream.index != streamIndex && encoder != null) subtitleEncoders[newSubtitleIndex] = encoder
                oldSubtitleIndex++
                if (stream.index != streamIndex) newSubtitleIndex++
            }
        }
        val warnings = plan.warnings.filterNot { it.stream.index == streamIndex } +
            OmittedStreamWarning(removed, "fallback_stream_dropped")
        return plan.copy(
            disposition = PlanDisposition.READY_WITH_WARNINGS,
            streamsToCopy = kept,
            warnings = warnings,
            subtitleEncodersByOutputIndex = subtitleEncoders,
        )
    }

    private val lastStatisticsUpdate = AtomicLong(0L)

    private fun publishFileProgress(
        name: String,
        index: Int,
        total: Int,
        succeeded: Int,
        failed: Int,
        skipped: Int,
        omittedTracks: Int,
        timeMillis: Long,
        durationSeconds: Double?,
        speed: String,
    ) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastStatisticsUpdate.get()
        if (now - previous < STATS_THROTTLE_MS || !lastStatisticsUpdate.compareAndSet(previous, now)) return
        val estimate = ProgressEstimator.estimate(timeMillis, durationSeconds, speed)
        val eta = estimate.etaSeconds?.let { applicationContext.getString(R.string.eta_seconds, it) }
            ?: applicationContext.getString(R.string.eta_unknown)
        val speedText = estimate.speed.ifBlank { applicationContext.getString(R.string.speed_unknown) }
        val text = applicationContext.getString(R.string.progress_file_percent, estimate.percent, speedText, eta)
        setProgressAsync(
            progressData(
                total = total,
                completed = index,
                succeeded = succeeded,
                failed = failed,
                skipped = skipped,
                omittedTracks = omittedTracks,
                currentName = name,
                stage = "remuxeando",
                filePercent = estimate.percent,
                speed = speedText,
                etaSeconds = estimate.etaSeconds ?: -1L,
            ),
        )
        if (NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(applicationContext.getString(R.string.app_name))
                .setContentText("$name · $text")
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, estimate.percent, true)
                .addAction(
                    R.drawable.ic_notification,
                    applicationContext.getString(R.string.action_cancel),
                    WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
                )
                .build()
            runCatching { NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification) }
        }
    }

    private suspend fun safeSetForeground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Android can reject a late foreground-service start; the visible UI and worker state continue.
        }
    }

    private fun thermalWarning(powerManager: PowerManager): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ""
        return if (powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            applicationContext.getString(R.string.warning_device_hot)
        } else ""
    }

    private fun formatError(code: RemuxErrorCode, container: OutputContainer, log: String = ""): String = when (code) {
        RemuxErrorCode.SIN_ESPACIO -> applicationContext.getString(R.string.error_no_space)
        RemuxErrorCode.STREAM_INCOMPATIBLE -> applicationContext.getString(
            R.string.error_stream_incompatible,
            container.label,
            STREAM_CODEC_REGEX.find(log)?.groupValues?.getOrNull(1) ?: applicationContext.getString(R.string.codec_unknown),
        )
        RemuxErrorCode.ENTRADA_INVALIDA -> applicationContext.getString(R.string.error_invalid_input)
        RemuxErrorCode.PERMISO -> applicationContext.getString(R.string.error_permission)
        RemuxErrorCode.DESCONOCIDO -> applicationContext.getString(R.string.error_unknown_cause)
    }

    private fun formatWarnings(warnings: List<OmittedStreamWarning>): String =
        warnings.joinToString(" ") { warning ->
            when {
                warning.detail == "fallback_stream_dropped" -> applicationContext.getString(
                    R.string.warning_fallback_stream, warning.stream.index,
                )
                warning.detail == "subtitle_encoder_missing" -> applicationContext.getString(
                    R.string.warning_subtitle_encoder_missing, warning.stream.index,
                )
                warning.detail.startsWith("subtitle_converted:") -> applicationContext.getString(
                    R.string.warning_subtitle_conversion,
                    warning.stream.index,
                    warning.detail.substringAfter(':').uppercase(Locale.ROOT),
                )
                else -> formatWarning(warning)
            }
        }

    private fun omittedTrackCount(warnings: List<OmittedStreamWarning>): Int =
        warnings.count { !it.detail.startsWith("subtitle_converted:") }

    private suspend fun copyFileToDocument(source: File, destination: DocumentFile) {
        withContext(Dispatchers.IO) {
            val workContext = currentCoroutineContext()
            source.inputStream().use { input ->
                val output = applicationContext.contentResolver.openOutputStream(destination.uri, "w")
                    ?: throw IOException(applicationContext.getString(R.string.worker_write_final_error))
                output.use { target ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        workContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        target.write(buffer, 0, count)
                    }
                    target.flush()
                }
            }
        }
    }

    private class FfmpegFailure(val logs: String, val failStackTrace: String) : IOException()

    private suspend fun validateOutput(
        file: DocumentFile,
        plan: RemuxPlan,
        source: ProbedMedia,
    ) {
        if (file.length() == 0L) throw IOException(applicationContext.getString(R.string.worker_empty_output))
        val outputPath = FFmpegKitConfig.getSafParameterForRead(applicationContext, file.uri)
        validateOutput(outputPath, plan, source)
    }

    private suspend fun validateOutput(
        outputPath: String,
        plan: RemuxPlan,
        source: ProbedMedia,
    ) {
        val output = probe(outputPath)
        if (output.streams.size != plan.streamsToCopy.size) {
            throw IOException(applicationContext.getString(R.string.worker_output_track_count))
        }
        val expectedVideoTracks = plan.streamsToCopy.count { it.type == MediaStreamType.VIDEO }
        val outputVideoTracks = output.streams.count { it.type == MediaStreamType.VIDEO }
        if (outputVideoTracks != expectedVideoTracks) {
            throw IOException(applicationContext.getString(R.string.worker_output_video_count))
        }
        var subtitleIndex = 0
        val expectedTracks = plan.streamsToCopy.map { stream ->
            val outputCodec = if (stream.type == MediaStreamType.SUBTITLE) {
                plan.subtitleEncodersByOutputIndex[subtitleIndex++]?.let(::subtitleCodecForEncoder) ?: stream.codec
            } else stream.codec
            "${stream.type}:$outputCodec"
        }.sorted()
        val actualTracks = output.streams.map { "${it.type}:${it.codec}" }.sorted()
        if (actualTracks != expectedTracks) {
            throw IOException(applicationContext.getString(R.string.worker_output_track_count))
        }
        val inputDuration = source.durationSeconds
        val outputDuration = output.durationSeconds
        if (inputDuration != null && outputDuration != null) {
            val tolerance = maxOf(1.0, inputDuration * 0.01)
            if (kotlin.math.abs(inputDuration - outputDuration) > tolerance) {
                throw IOException(applicationContext.getString(R.string.worker_output_duration))
            }
        }
    }

    private suspend fun isReusableOutput(
        file: DocumentFile,
        plan: RemuxPlan,
        source: ProbedMedia,
    ): Boolean = try {
        validateOutput(file, plan, source)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun formatRejection(plan: RemuxPlan, target: OutputContainer): String = when (plan.rejection) {
        PlanRejection.NO_VIDEO_TRACK -> applicationContext.getString(R.string.error_no_video_track)
        PlanRejection.VIDEO_CODEC_UNSUPPORTED -> applicationContext.getString(
            R.string.error_video_codec_unsupported,
            target.label,
            plan.rejectedVideoCodecs.joinToString(),
        )
        PlanRejection.AUDIO_UNSUPPORTED -> applicationContext.getString(R.string.error_audio_unsupported, target.label)
        null -> applicationContext.getString(R.string.error_remux_generic)
    }

    private fun subtitleCodecForEncoder(encoder: String): String = when (encoder) {
        "srt", "subrip" -> "subrip"
        else -> encoder
    }

    private fun formatWarning(warning: OmittedStreamWarning): String {
        val type = when (warning.stream.type) {
            MediaStreamType.VIDEO -> R.string.stream_type_video
            MediaStreamType.AUDIO -> R.string.stream_type_audio
            MediaStreamType.SUBTITLE -> R.string.stream_type_subtitles
            MediaStreamType.DATA -> R.string.stream_type_data
            MediaStreamType.ATTACHMENT -> R.string.stream_type_attachment
            MediaStreamType.UNKNOWN -> R.string.stream_type_unknown
        }
        val codec = warning.stream.codec.ifBlank {
            applicationContext.getString(R.string.codec_unknown)
        }
        if (warning.detail == "fallback_stream_dropped") {
            return applicationContext.getString(R.string.warning_fallback_stream, warning.stream.index)
        }
        if (warning.detail == "subtitle_encoder_missing") {
            return applicationContext.getString(R.string.warning_subtitle_encoder_missing, warning.stream.index)
        }
        if (warning.detail.startsWith("subtitle_converted:")) {
            return applicationContext.getString(
                R.string.warning_subtitle_conversion,
                warning.stream.index,
                warning.detail.substringAfter(':').uppercase(Locale.ROOT),
            )
        }
        return applicationContext.getString(
            R.string.warning_stream_omitted,
            warning.stream.index,
            applicationContext.getString(type),
            codec,
        )
    }

    private suspend fun publishValidatedFile(
        directory: DocumentFile,
        partialFile: DocumentFile,
        finalName: String,
        plan: RemuxPlan,
        sourceMedia: ProbedMedia,
    ): DocumentFile {
        if (partialFile.renameTo(finalName)) {
            val renamed = directory.findFile(finalName)
            if (renamed != null && renamed.name == finalName) return renamed
            if (partialFile.name == finalName && partialFile.exists()) return partialFile
            if (partialFile.exists()) partialFile.renameTo(finalName)
            val corrected = directory.findFile(finalName)
            if (corrected != null && corrected.name == finalName) return corrected
            throw IOException(applicationContext.getString(R.string.worker_final_name_mismatch, finalName))
        }

        val copyPartName = "$finalName.publish.part"
        val copyPart = directory.createFile("application/octet-stream", copyPartName)
            ?: throw IOException(applicationContext.getString(R.string.worker_publish_error))
        if (copyPart.name != copyPartName) {
            copyPart.delete()
            throw IOException(applicationContext.getString(R.string.worker_name_mismatch, copyPart.name ?: "?", copyPartName))
        }
        try {
            val bytesCopied = copyDocument(partialFile, copyPart)
            if (bytesCopied <= 0L) throw IOException(applicationContext.getString(R.string.worker_copy_error))
            validateOutput(copyPart, plan, sourceMedia)
            if (!copyPart.renameTo(finalName)) throw IOException(applicationContext.getString(R.string.worker_publish_error))
            val finalFile = (directory.findFile(finalName) ?: (if (copyPart.name == finalName) copyPart else null))
                ?: throw IOException(applicationContext.getString(R.string.worker_rename_missing))
            if (finalFile.name != finalName) throw IOException(applicationContext.getString(R.string.worker_final_name_mismatch, finalName))
            partialFile.delete()
            return finalFile
        } catch (exception: Exception) {
            copyPart.delete()
            throw exception
        }
    }

    private suspend fun copyDocument(source: DocumentFile, destination: DocumentFile): Long =
        withContext(Dispatchers.IO) {
            val workContext = currentCoroutineContext()
            val input = applicationContext.contentResolver.openInputStream(source.uri)
                ?: throw IOException(applicationContext.getString(R.string.worker_read_partial_error))
            input.use { sourceStream ->
                val output = applicationContext.contentResolver.openOutputStream(destination.uri, "w")
                    ?: throw IOException(applicationContext.getString(R.string.worker_write_final_error))
                output.use { destinationStream ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        workContext.ensureActive()
                        val count = sourceStream.read(buffer)
                        if (count < 0) break
                        destinationStream.write(buffer, 0, count)
                        copied += count
                    }
                    destinationStream.flush()
                    copied
                }
            }
        }

    private fun getOrCreateOutputFolder(root: DocumentFile, name: String, legacyName: String): DocumentFile {
        val current = root.findFile(name)
        val legacy = root.findFile(legacyName)
        fun isMarked(folder: DocumentFile?): Boolean {
            if (folder == null || !folder.isDirectory) return false
            return folder.findFile(VideoScanner.OUTPUT_MARKER_FILE) != null
        }
        if (isMarked(current)) return current!!
        if (isMarked(legacy)) return legacy!!
        val existing = current ?: legacy
        if (existing != null) throw IOException(
            applicationContext.getString(R.string.worker_existing_output_error, existing.name ?: name),
        )
        val outputFolder = root.createDirectory(name)
            ?: throw IOException(applicationContext.getString(R.string.worker_output_folder_error, name))
        if (outputFolder.createFile("application/octet-stream", VideoScanner.OUTPUT_MARKER_FILE) == null) {
            outputFolder.delete()
            throw IOException(applicationContext.getString(R.string.worker_marker_error))
        }
        return outputFolder
    }

    private fun getOrCreateDirectory(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        if (existing != null) {
            if (existing.isDirectory) return existing
            throw IOException(applicationContext.getString(R.string.worker_blocked_folder_error, name))
        }
        return parent.createDirectory(name)
            ?: throw IOException(applicationContext.getString(R.string.worker_subfolder_error, name))
    }

    private fun uniqueFileName(existingNames: Set<String>, sourceName: String, extension: String): String {
        val base = FileNamePolicy.safeSegment(
            sourceName.substringBeforeLast('.', sourceName).ifBlank { applicationContext.getString(R.string.output_default_name) },
            applicationContext.getString(R.string.output_default_name),
        )
        val safeExtension = FileNamePolicy.safeSegment(extension, "mkv")
        var candidate = "$base.$safeExtension"
        var index = 2
        while (candidate in existingNames || "$candidate.part" in existingNames) {
            candidate = "$base ($index).$safeExtension"
            index++
        }
        return candidate
    }

    private fun outputFileName(sourceName: String, extension: String): String {
        return FileNamePolicy.outputName(sourceName, extension, applicationContext.getString(R.string.output_default_name))
    }

    private fun cleanupOrphanPartials(directory: DocumentFile) {
        directory.listFiles().forEach { child ->
            if (child.isDirectory) cleanupOrphanPartials(child)
            else if (child.name.orEmpty().endsWith(".part", ignoreCase = true)) child.delete()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.worker_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun foregroundInfo(text: String, total: Int, completed: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, completed.coerceAtMost(total), total > 0)
            .addAction(
                R.drawable.ic_notification,
                applicationContext.getString(R.string.action_cancel),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            .build()

        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            else -> ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun progressData(
        total: Int,
        completed: Int,
        succeeded: Int,
        failed: Int,
        skipped: Int,
        omittedTracks: Int,
        currentName: String?,
        stage: String,
        filePercent: Int = 0,
        speed: String = "",
        etaSeconds: Long = -1L,
    ): Data = Data.Builder()
        .putInt(KEY_TOTAL, total)
        .putInt(KEY_COMPLETED, completed)
        .putInt(KEY_SUCCEEDED, succeeded)
        .putInt(KEY_FAILED, failed)
        .putInt(KEY_SKIPPED, skipped)
        .putInt(KEY_OMITTED_TRACKS, omittedTracks)
        .putString(KEY_CURRENT_NAME, currentName)
        .putString(KEY_STAGE, stage)
        .putInt(KEY_FILE_PERCENT, filePercent)
        .putString(KEY_SPEED, speed)
        .putLong(KEY_ETA_SECONDS, etaSeconds)
        .build()

    private fun errorData(message: String): Data =
        Data.Builder().putString(KEY_ERROR, message).build()

    companion object {
        const val UNIQUE_WORK_NAME = "remux-folder-batch"
        const val TAG = "remux-folder"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_CONTAINER = "container"
        const val KEY_TOTAL = "total"
        const val KEY_COMPLETED = "completed"
        const val KEY_SUCCEEDED = "succeeded"
        const val KEY_FAILED = "failed"
        const val KEY_SKIPPED = "skipped"
        const val KEY_OMITTED_TRACKS = "omitted_tracks"
        const val KEY_CURRENT_NAME = "current_name"
        const val KEY_STAGE = "stage"
        const val KEY_FAILURE_DETAILS = "failure_details"
        const val KEY_WARNING_DETAILS = "warning_details"
        const val KEY_PREFLIGHT = "preflight_only"
        const val KEY_IS_PREFLIGHT = "is_preflight"
        const val KEY_PREFLIGHT_DETAILS = "preflight_details"
        const val KEY_PREFLIGHT_DETAIL_COUNT = "preflight_detail_count"
        const val KEY_READY_COUNT = "ready_count"
        const val KEY_PARTIAL_COUNT = "partial_count"
        const val KEY_FAILURE_DETAIL_COUNT = "failure_detail_count"
        const val KEY_WARNING_DETAIL_COUNT = "warning_detail_count"
        const val KEY_ERROR = "error"
        const val KEY_ALLOW_DROP_ALL_AUDIO = "allow_drop_all_audio"
        const val KEY_RETRY_ONLY_FAILED = "retry_only_failed"
        const val KEY_RESULT_KEY = "result_key"
        const val KEY_FILE_PERCENT = "file_percent"
        const val KEY_SPEED = "speed"
        const val KEY_ETA_SECONDS = "eta_seconds"

        private const val MAX_RESULT_DETAILS = 8
        private const val MAX_RESULT_DETAIL_LENGTH = 400
        private const val COPY_BUFFER_SIZE = 128 * 1024
        private const val SESSION_CANCEL_WAIT_MS = 30_000L
        private const val CHANNEL_ID = "remux_progress"
        private const val NOTIFICATION_ID = 1001
        private const val COMPLETION_NOTIFICATION_ID = 1002
        private const val MAX_LOG_CHARS = 8_000
        private const val STATS_THROTTLE_MS = 800L
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private val OUTPUT_STREAM_INDEX_REGEX = Regex(
            "Could not find tag for codec\\s+[^\\s,]+\\s+in stream #(\\d+)",
            RegexOption.IGNORE_CASE,
        )
        private val INPUT_STREAM_INDEX_REGEX = Regex("Stream #0:(\\d+)", RegexOption.IGNORE_CASE)
        private val STREAM_CODEC_REGEX = Regex("Could not find tag for codec\\s+([^\\s,]+)", RegexOption.IGNORE_CASE)
        private val TIMESTAMP_REPAIR_EXTENSIONS = setOf(
            "ts", "m2ts", "mts", "m2t", "avi", "mpg", "mpeg", "m2p", "vob", "ps", "mpegps", "flv", "f4v",
        )
        private val TIMESTAMP_REPAIR_FORMATS = setOf("mpegts", "avi", "mpeg", "mpegvideo", "flv")
        private val CHAPTER_CONTAINERS = setOf(OutputContainer.MP4, OutputContainer.MOV, OutputContainer.MKV)
    }

}
