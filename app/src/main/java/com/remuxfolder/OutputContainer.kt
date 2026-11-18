package com.remuxfolder

enum class OutputContainer(
    val id: String,
    val label: String,
    val extension: String,
    val muxer: String,
    val mimeType: String,
) {
    MP4("mp4", "MP4", "mp4", "mp4", "video/mp4"),
    MKV("mkv", "MKV", "mkv", "matroska", "video/x-matroska"),
    MOV("mov", "MOV", "mov", "mov", "video/quicktime"),
    TS("ts", "TS", "ts", "mpegts", "video/mp2t"),
    AVI("avi", "AVI", "avi", "avi", "video/x-msvideo"),
    WEBM("webm", "WebM", "webm", "webm", "video/webm");

    companion object {
        fun fromId(id: String?): OutputContainer =
            entries.firstOrNull { it.id == id } ?: MP4
    }
}
