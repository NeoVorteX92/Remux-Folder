package com.remuxfolder

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class RemuxFileResult(
    val sourceUri: String,
    val sourceName: String,
    val status: String,
    val reasonCode: String = "",
    val reason: String = "",
    val warning: String = "",
    val log: String = "",
    val outputName: String = "",
) {
    val isFailed: Boolean get() = status == "ERROR"
}

/** Full results live in app storage; WorkManager Data only carries a small key. */
object RemuxResultStore {
    private const val MAX_LOG_CHARS = 8_000

    fun key(treeUri: String, containerId: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$treeUri|$containerId".toByteArray(Charsets.UTF_8))
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun read(context: Context, key: String): List<RemuxFileResult> {
        val file = resultFile(context, key)
        if (!file.exists()) return emptyList()
        return runCatching {
            val content = file.readText(Charsets.UTF_8)
            if (content.trimStart().startsWith("[")) readLegacyArray(content)
            else content.lineSequence().mapNotNull { line -> runCatching { decode(JSONObject(line)) }.getOrNull() }
                .associateBy(RemuxFileResult::sourceUri).values.toList()
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun upsert(context: Context, key: String, result: RemuxFileResult) {
        val safeResult = result.copy(log = result.log.takeLast(MAX_LOG_CHARS))
        val file = resultFile(context, key)
        file.appendText(encode(safeResult).toString() + "\n", Charsets.UTF_8)
    }

    @Synchronized
    fun replace(context: Context, key: String, results: List<RemuxFileResult>) = write(context, key, results)

    fun exportText(results: List<RemuxFileResult>): String = buildString {
        appendLine("Remux Folder — resultados")
        results.forEach { item ->
            appendLine("[${item.status}] ${item.sourceName}")
            if (item.reason.isNotBlank()) appendLine("Motivo: ${item.reason}")
            if (item.warning.isNotBlank()) appendLine("Avisos: ${item.warning}")
            if (item.outputName.isNotBlank()) appendLine("Salida: ${item.outputName}")
            if (item.log.isNotBlank()) appendLine("Registro: ${item.log}")
            appendLine()
        }
    }

    fun exportFile(context: Context, key: String, results: List<RemuxFileResult>): File {
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        return File(directory, "remux-folder-$key.txt").apply {
            writeText(exportText(results), Charsets.UTF_8)
        }
    }

    @Synchronized
    fun writeSelection(context: Context, key: String, uris: Set<String>) {
        val file = AtomicFile(File(context.filesDir, "remux-selection-$key.json"))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            val json = JSONArray()
            uris.forEach { json.put(it) }
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (exception: Exception) {
            if (stream != null) file.failWrite(stream)
            throw exception
        }
    }

    @Synchronized
    fun readSelection(context: Context, key: String): Set<String>? {
        val file = File(context.filesDir, "remux-selection-$key.json")
        if (!file.exists()) return null
        return runCatching {
            val json = JSONArray(file.readText(Charsets.UTF_8))
            buildSet { for (index in 0 until json.length()) add(json.getString(index)) }
        }.getOrNull()
    }

    private fun write(context: Context, key: String, values: List<RemuxFileResult>) {
        val atomicFile = AtomicFile(resultFile(context, key))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            val text = buildString {
            values.forEach { value ->
                append(encode(value).toString()).append('\n')
            }
            }
            stream.write(text.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (exception: Exception) {
            if (stream != null) atomicFile.failWrite(stream)
            throw exception
        }
    }

    private fun readLegacyArray(content: String): List<RemuxFileResult> {
        val json = JSONArray(content)
        return List(json.length()) { index -> decode(json.getJSONObject(index)) }
    }

    private fun encode(value: RemuxFileResult) = JSONObject()
        .put("sourceUri", value.sourceUri)
        .put("sourceName", value.sourceName)
        .put("status", value.status)
        .put("reasonCode", value.reasonCode)
        .put("reason", value.reason)
        .put("warning", value.warning)
        .put("log", value.log.takeLast(MAX_LOG_CHARS))
        .put("outputName", value.outputName)

    private fun decode(item: JSONObject) = RemuxFileResult(
        sourceUri = item.optString("sourceUri"),
        sourceName = item.optString("sourceName"),
        status = item.optString("status"),
        reasonCode = item.optString("reasonCode"),
        reason = item.optString("reason"),
        warning = item.optString("warning"),
        log = item.optString("log"),
        outputName = item.optString("outputName"),
    )

    private fun resultFile(context: Context, key: String) = File(context.filesDir, "remux-results-$key.json")
}
