package com.remuxfolder

import java.util.Locale

enum class MediaStreamType {
    VIDEO,
    AUDIO,
    SUBTITLE,
    DATA,
    ATTACHMENT,
    UNKNOWN,
}

data class MediaStream(
    val index: Int,
    val type: MediaStreamType,
    val codec: String,
    val attachedPicture: Boolean = false,
)

data class ProbedMedia(
    val formatNames: Set<String>,
    val majorBrand: String?,
    val durationSeconds: Double?,
    val streams: List<MediaStream>,
)

enum class PlanDisposition {
    READY,
    READY_WITH_WARNINGS,
    SKIP_SAME_CONTAINER,
    REJECTED,
}

enum class PlanRejection {
    NO_VIDEO_TRACK,
    VIDEO_CODEC_UNSUPPORTED,
    AUDIO_UNSUPPORTED,
}

data class OmittedStreamWarning(
    val stream: MediaStream,
    val detail: String = "",
)

data class RemuxPlan(
    val disposition: PlanDisposition,
    val streamsToCopy: List<MediaStream>,
    val warnings: List<OmittedStreamWarning>,
    val rejection: PlanRejection? = null,
    val rejectedVideoCodecs: List<String> = emptyList(),
    val subtitleEncodersByOutputIndex: Map<Int, String> = emptyMap(),
) {
    val canRemux: Boolean
        get() = disposition == PlanDisposition.READY ||
            disposition == PlanDisposition.READY_WITH_WARNINGS

    fun ffmpegMapArguments(): List<String> = streamsToCopy.flatMap { stream ->
        listOf("-map", "0:${stream.index}")
    }

    fun ffmpegSubtitleArguments(): List<String> = subtitleEncodersByOutputIndex.flatMap { (index, encoder) ->
        listOf("-c:s:$index", encoder)
    }

    fun ffmpegVideoTagArguments(target: OutputContainer): List<String> {
        if (target != OutputContainer.MP4 && target != OutputContainer.MOV) return emptyList()
        return streamsToCopy.filter { it.type == MediaStreamType.VIDEO }
            .mapIndexedNotNull { outputIndex, stream ->
                if (stream.codec == "hevc" && !stream.attachedPicture) listOf("-tag:v:$outputIndex", "hvc1") else null
            }.flatten()
    }
}

/**
 * Pure preflight rules. MKV deliberately lets its muxer try less common audio/video codecs;
 * FFmpeg's result and the worker's one-stream fallback remain the final check.
 */
object MediaRemuxPlanner {
    private val videoCodecs = mapOf(
        OutputContainer.MP4 to setOf("h264", "hevc", "av1", "vp9", "mpeg4"),
        OutputContainer.MKV to setOf(
            "h264", "hevc", "av1", "vp8", "vp9", "mpeg1video", "mpeg2video", "mpeg4",
            "mjpeg", "prores", "ffv1", "vc1", "theora", "huffyuv", "ffvhuff", "rawvideo",
        ),
        OutputContainer.MOV to setOf(
            "h264", "hevc", "av1", "vp9", "mpeg4", "mjpeg", "prores", "png", "qtrle",
            "rawvideo", "cinepak", "dvvideo",
        ),
        OutputContainer.TS to setOf("h264", "hevc", "mpeg1video", "mpeg2video"),
        OutputContainer.AVI to setOf(
            "h264", "mpeg4", "mpeg1video", "mpeg2video", "mjpeg", "msmpeg4v3", "ffv1",
            "huffyuv", "rawvideo", "dvvideo",
        ),
        OutputContainer.WEBM to setOf("vp8", "vp9", "av1"),
    )

    private val audioCodecs = mapOf(
        OutputContainer.MP4 to setOf("aac", "mp3", "ac3", "eac3", "alac", "opus"),
        OutputContainer.MKV to setOf(
            "aac", "mp3", "ac3", "eac3", "dts", "flac", "opus", "vorbis", "truehd", "alac",
            "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_s16be", "pcm_s24be",
            "pcm_s32be", "pcm_f32be", "mp2", "amr_nb", "amr_wb",
        ),
        OutputContainer.MOV to setOf(
            "aac", "mp3", "ac3", "eac3", "alac", "pcm_s16le", "pcm_s24le", "pcm_s32le",
            "pcm_f32le", "pcm_s16be", "pcm_s24be", "pcm_s32be", "pcm_f32be", "adpcm_ima_qt",
        ),
        OutputContainer.TS to setOf("aac", "mp2", "mp3", "ac3", "eac3", "dts"),
        OutputContainer.AVI to setOf(
            "mp3", "ac3", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_u8", "adpcm_ima_wav",
            "adpcm_ms", "vorbis",
        ),
        OutputContainer.WEBM to setOf("opus", "vorbis"),
    )

    fun plan(
        media: ProbedMedia,
        sourceExtension: String,
        target: OutputContainer,
        allowDropAllAudio: Boolean = false,
        availableSubtitleEncoders: Set<String> = DEFAULT_SUBTITLE_ENCODERS,
    ): RemuxPlan {
        val movingVideo = media.streams.filter { it.type == MediaStreamType.VIDEO && !it.attachedPicture }
        if (movingVideo.isEmpty()) {
            return rejected(PlanRejection.NO_VIDEO_TRACK)
        }

        val sameContainer = sourceExtension.lowercase(Locale.ROOT) in sourceExtensionsFor(target) &&
            media.formatNames.any { formatMatchesTarget(it, target) } &&
            !isQuickTimeBrandMismatch(media.majorBrand, target)
        if (sameContainer) {
            return RemuxPlan(
                disposition = PlanDisposition.SKIP_SAME_CONTAINER,
                streamsToCopy = emptyList(),
                warnings = emptyList(),
            )
        }

        val selected = media.streams.mapNotNull { stream ->
            val compatible = when {
                stream.type == MediaStreamType.VIDEO && stream.attachedPicture -> target in setOf(
                    OutputContainer.MP4, OutputContainer.MKV, OutputContainer.MOV,
                )
                stream.type == MediaStreamType.VIDEO -> stream.codec in videoCodecs.getValue(target) ||
                    (target == OutputContainer.MKV && stream.codec.isNotBlank())
                stream.type == MediaStreamType.AUDIO -> stream.codec in audioCodecs.getValue(target) ||
                    (target == OutputContainer.MKV && stream.codec.isNotBlank())
                stream.type == MediaStreamType.SUBTITLE -> subtitleCodecCompatible(stream, target)
                stream.type == MediaStreamType.ATTACHMENT -> target == OutputContainer.MKV
                else -> false
            }
            if (!compatible) return@mapNotNull null
            if (stream.type == MediaStreamType.SUBTITLE) {
                val requiredEncoder = subtitleEncoder(stream.codec, target)
                if (requiredEncoder != null && requiredEncoder !in availableSubtitleEncoders) return@mapNotNull null
            }
            stream
        }
        val compatible = selected
        val omitted = media.streams.filterNot { it in compatible }
        val videoKept = compatible.any { it.type == MediaStreamType.VIDEO && !it.attachedPicture }
        if (!videoKept) {
            return rejected(
                rejection = PlanRejection.VIDEO_CODEC_UNSUPPORTED,
                codecs = movingVideo.map { it.codec.ifBlank { "unknown" } },
            )
        }
        val inputHasAudio = media.streams.any { it.type == MediaStreamType.AUDIO }
        val outputHasAudio = compatible.any { it.type == MediaStreamType.AUDIO }
        if (inputHasAudio && !outputHasAudio && !allowDropAllAudio) {
            return rejected(PlanRejection.AUDIO_UNSUPPORTED)
        }

        val warnings = omitted.map { stream ->
            val detail = if (stream.type == MediaStreamType.SUBTITLE && subtitleEncoder(stream.codec, target) != null &&
                subtitleEncoder(stream.codec, target) !in availableSubtitleEncoders
            ) "subtitle_encoder_missing" else ""
            OmittedStreamWarning(stream, detail)
        }.toMutableList()
        val subtitleEncoders = mutableMapOf<Int, String>()
        var subtitleOutputIndex = 0
        compatible.filter { it.type == MediaStreamType.SUBTITLE }.forEach { stream ->
            subtitleEncoder(stream.codec, target)?.let { subtitleEncoders[subtitleOutputIndex] = it }
            subtitleOutputIndex++
        }
        compatible.filter { it.type == MediaStreamType.SUBTITLE }.forEach { stream ->
            if (subtitleEncoder(stream.codec, target) != null) {
                warnings += OmittedStreamWarning(stream, "subtitle_converted:${subtitleEncoder(stream.codec, target)}")
            }
        }
        return RemuxPlan(
            disposition = if (warnings.isEmpty()) PlanDisposition.READY else PlanDisposition.READY_WITH_WARNINGS,
            streamsToCopy = compatible,
            warnings = warnings,
            subtitleEncodersByOutputIndex = subtitleEncoders,
        )
    }

    private fun subtitleCodecCompatible(stream: MediaStream, target: OutputContainer): Boolean {
        if (stream.codec in setOf("dvd_subtitle", "hdmv_pgs_subtitle", "dvb_subtitle", "dvb_teletext", "xsub")) {
            return target == OutputContainer.MKV || target == OutputContainer.TS
        }
        return when (target) {
            OutputContainer.MP4, OutputContainer.MOV -> stream.codec in setOf("mov_text", "subrip", "ass", "ssa", "webvtt")
            OutputContainer.MKV -> stream.codec in setOf("mov_text", "subrip", "ass", "ssa", "webvtt")
            OutputContainer.TS -> stream.codec in setOf("dvb_subtitle", "dvb_teletext")
            OutputContainer.AVI -> false
            OutputContainer.WEBM -> stream.codec in setOf("webvtt", "subrip", "ass", "ssa", "mov_text")
        }
    }

    private fun subtitleEncoder(codec: String, target: OutputContainer): String? = when {
        target == OutputContainer.MP4 || target == OutputContainer.MOV ->
            if (codec in setOf("subrip", "ass", "ssa", "webvtt")) "mov_text" else null
        target == OutputContainer.MKV && codec == "mov_text" -> "srt"
        target == OutputContainer.WEBM && codec != "webvtt" -> "webvtt"
        else -> null
    }

    private fun formatMatchesTarget(format: String, target: OutputContainer): Boolean {
        val names = format.lowercase(Locale.ROOT).split(',').map { it.trim() }
        return when (target) {
            OutputContainer.MP4, OutputContainer.MOV -> "mov" in names || "mp4" in names
            OutputContainer.MKV -> "matroska" in names
            OutputContainer.TS -> "mpegts" in names
            OutputContainer.AVI -> "avi" in names
            OutputContainer.WEBM -> "webm" in names
        }
    }

    private fun sourceExtensionsFor(target: OutputContainer): Set<String> = when (target) {
        OutputContainer.MP4 -> setOf("mp4", "m4v")
        OutputContainer.MKV -> setOf("mkv")
        OutputContainer.MOV -> setOf("mov")
        OutputContainer.TS -> setOf("ts", "m2ts", "mts", "m2t")
        OutputContainer.AVI -> setOf("avi")
        OutputContainer.WEBM -> setOf("webm")
    }

    private fun isQuickTimeBrandMismatch(majorBrand: String?, target: OutputContainer): Boolean {
        val isQuickTime = majorBrand?.trim() == "qt"
        return (target == OutputContainer.MP4 && isQuickTime) ||
            (target == OutputContainer.MOV && !isQuickTime && majorBrand != null)
    }

    private fun rejected(
        rejection: PlanRejection,
        codecs: List<String> = emptyList(),
    ) = RemuxPlan(
        disposition = PlanDisposition.REJECTED,
        streamsToCopy = emptyList(),
        warnings = emptyList(),
        rejection = rejection,
        rejectedVideoCodecs = codecs,
    )

    val DEFAULT_SUBTITLE_ENCODERS = setOf("mov_text", "subrip", "srt", "webvtt", "ass")
}
