package com.remuxfolder

import org.json.JSONObject
import java.util.Locale

object MediaProbeParser {
    fun parse(json: String): ProbedMedia {
        val root = JSONObject(json)
        val format = root.optJSONObject("format") ?: JSONObject()
        val tags = format.optJSONObject("tags") ?: JSONObject()
        val formatNames = format.optString("format_name")
            .split(',')
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toSet()
        val streamsJson = root.optJSONArray("streams")
            ?: throw IllegalArgumentException("FFprobe no devolvió pistas.")
        val streams = buildList {
            for (position in 0 until streamsJson.length()) {
                val stream = streamsJson.optJSONObject(position) ?: continue
                val index = stream.optInt("index", -1)
                if (index < 0) continue
                val type = when (stream.optString("codec_type").lowercase(Locale.ROOT)) {
                    "video" -> MediaStreamType.VIDEO
                    "audio" -> MediaStreamType.AUDIO
                    "subtitle" -> MediaStreamType.SUBTITLE
                    "data" -> MediaStreamType.DATA
                    "attachment" -> MediaStreamType.ATTACHMENT
                    else -> MediaStreamType.UNKNOWN
                }
                val disposition = stream.optJSONObject("disposition") ?: JSONObject()
                add(
                    MediaStream(
                        index = index,
                        type = type,
                        codec = stream.optString("codec_name").lowercase(Locale.ROOT),
                        attachedPicture = disposition.optInt("attached_pic", 0) == 1,
                    ),
                )
            }
        }.sortedBy(MediaStream::index)
        val duration = format.optString("duration")
            .toDoubleOrNull()
            ?.takeIf { it.isFinite() }

        return ProbedMedia(
            formatNames = formatNames,
            majorBrand = tags.optString("major_brand").takeIf { it.isNotBlank() },
            durationSeconds = duration,
            streams = streams,
        )
    }
}
