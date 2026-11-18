package com.remuxfolder

/** Names used for the output directory inside the selected source directory. */
object OutputFolderNaming {
    fun current(rootName: String?): String = "${FileNamePolicy.safeSegment(rootName.orEmpty(), "Videos")} (remux)"

    fun legacy(rootName: String?): String = "${rootName?.takeIf { it.isNotBlank() } ?: "Videos"}remux"
}

object FileNamePolicy {
    private val invalidCharacters = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    fun safeSegment(value: String, fallback: String = "video"): String {
        val cleaned = value.map { if (it in invalidCharacters || it.code < 32) '_' else it }
            .joinToString("")
            .trim()
            .trim('.')
            .take(140)
            .trimEnd()
        return cleaned.ifBlank { fallback }
    }

    fun outputName(sourceName: String, extension: String, fallback: String = "video"): String {
        val base = sourceName.substringBeforeLast('.', sourceName).ifBlank { fallback }
        return "${safeSegment(base, fallback)}.${safeSegment(extension, "mkv")}"
    }
}
