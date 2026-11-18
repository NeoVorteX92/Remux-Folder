package com.remuxfolder

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException
import java.util.Locale

data class SourceVideo(
    val uri: Uri,
    val name: String,
    val relativeDirectories: List<String>,
    val sizeBytes: Long = 0L,
    val lastModified: Long = 0L,
)

data class VideoScanResult(val videos: List<SourceVideo>, val totalBytes: Long) {
    val count: Int get() = videos.size
}

object VideoScanner {
    const val OUTPUT_MARKER_FILE = ".remux-folder-output"

    private val knownVideoExtensions = setOf(
        "264", "265", "3g2", "3gp", "3gp2", "asf", "avi", "amv", "divx",
        "dv", "dvr-ms", "f4v", "flv", "h264", "h265", "hevc", "ivf",
        "m2p", "m2t", "m2ts", "m2v", "m4v", "mjpeg", "mjpg", "mkv",
        "mod", "mov", "mp4", "mpe", "mpeg", "mpeg1", "mpeg2", "mpg",
        "mpv", "mts", "mxf", "ogm", "ogv", "ps", "mpegps", "rm", "rmvb", "tod", "ts",
        "vob", "webm", "wmv", "wtv", "y4m",
    )

    fun scan(context: Context, treeUri: Uri): List<SourceVideo> = scanDetailed(context, treeUri).videos

    /** Queries each folder once for all needed columns, including file size and date. */
    fun scanDetailed(context: Context, treeUri: Uri): VideoScanResult {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrElse { throw IOException("No se pudo abrir la carpeta seleccionada.", it) }
        val rootName = queryDisplayName(context, treeUri, rootId)
        val currentOutput = OutputFolderNaming.current(rootName)
        val legacyOutput = OutputFolderNaming.legacy(rootName)
        val videos = mutableListOf<SourceVideo>()

        data class Child(val id: String, val name: String, val mime: String, val size: Long, val modified: Long)

        fun children(parentId: String): List<Child> {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val columns = arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED,
            )
            val cursor = context.contentResolver.query(childrenUri, columns, null, null, null)
                ?: throw IOException("El proveedor no pudo leer una carpeta.")
            return cursor.use { result ->
                val idColumn = result.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
                val nameColumn = result.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = result.getColumnIndex(Document.COLUMN_MIME_TYPE)
                val sizeColumn = result.getColumnIndex(Document.COLUMN_SIZE)
                val modifiedColumn = result.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
            buildList {
                    while (result.moveToNext()) {
                        val id = result.getString(idColumn) ?: continue
                        val name = result.getString(nameColumn) ?: continue
                        add(
                            Child(
                                id = id,
                                name = name,
                                mime = if (mimeColumn >= 0) result.getString(mimeColumn).orEmpty() else "",
                                size = if (sizeColumn >= 0 && !result.isNull(sizeColumn)) result.getLong(sizeColumn).coerceAtLeast(0L) else 0L,
                                modified = if (modifiedColumn >= 0 && !result.isNull(modifiedColumn)) result.getLong(modifiedColumn) else 0L,
                            ),
                        )
                    }
                }
            }.sortedWith(compareBy<Child>({ it.name.lowercase(Locale.ROOT) }, Child::name, Child::id))
        }

        fun visit(parentId: String, relativeDirectories: List<String>, rootChildren: List<Child>? = null) {
            val entries = rootChildren ?: children(parentId)
            for (child in entries) {
                if (child.mime == Document.MIME_TYPE_DIR) {
                    val isOutputCandidate = relativeDirectories.isEmpty() &&
                        (child.name == currentOutput || child.name == legacyOutput)
                    if (isOutputCandidate) {
                        val outputChildren = children(child.id)
                        if (outputChildren.any { it.name == OUTPUT_MARKER_FILE }) continue
                        visit(child.id, relativeDirectories + child.name, outputChildren)
                    } else {
                        visit(child.id, relativeDirectories + child.name)
                    }
                } else if (!child.name.endsWith(".part", ignoreCase = true) && isVideo(child.mime, child.name)) {
                    videos += SourceVideo(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id),
                        name = child.name,
                        relativeDirectories = relativeDirectories,
                        sizeBytes = child.size,
                        lastModified = child.modified,
                    )
                }
            }
        }

        visit(rootId, emptyList())
        return VideoScanResult(videos, videos.sumOf(SourceVideo::sizeBytes))
    }

    private fun queryDisplayName(context: Context, treeUri: Uri, documentId: String): String? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return context.contentResolver.query(
            uri,
            arrayOf(Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun isVideo(mimeType: String, name: String): Boolean {
        if (mimeType.startsWith("video/", ignoreCase = true)) return true
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in knownVideoExtensions
    }
}
