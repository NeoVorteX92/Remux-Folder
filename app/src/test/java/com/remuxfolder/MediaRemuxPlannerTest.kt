package com.remuxfolder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRemuxPlannerTest {
    @Test
    fun mp4ConvertsTextSubtitleAndReportsTheConversion() {
        val media = media(
            MediaStream(0, MediaStreamType.VIDEO, "h264"),
            MediaStream(1, MediaStreamType.AUDIO, "aac"),
            MediaStream(2, MediaStreamType.SUBTITLE, "subrip"),
        )

        val plan = MediaRemuxPlanner.plan(media, "mkv", OutputContainer.MP4)

        assertEquals(PlanDisposition.READY_WITH_WARNINGS, plan.disposition)
        assertEquals(listOf("-map", "0:0", "-map", "0:1", "-map", "0:2"), plan.ffmpegMapArguments())
        assertEquals(listOf("-c:s:0", "mov_text"), plan.ffmpegSubtitleArguments())
        assertEquals(1, plan.warnings.size)
        assertEquals(2, plan.warnings.single().stream.index)
    }

    @Test
    fun webmRejectsH264InsteadOfAttemptingAnImpossibleRemux() {
        val media = media(MediaStream(0, MediaStreamType.VIDEO, "h264"))

        val plan = MediaRemuxPlanner.plan(media, "mp4", OutputContainer.WEBM)

        assertEquals(PlanDisposition.REJECTED, plan.disposition)
        assertFalse(plan.canRemux)
        assertEquals(PlanRejection.VIDEO_CODEC_UNSUPPORTED, plan.rejection)
        assertTrue(plan.rejectedVideoCodecs.contains("h264"))
    }

    @Test
    fun matroskaRetainsKnownVideoAudioAndSubtitleStreams() {
        val media = media(
            MediaStream(0, MediaStreamType.VIDEO, "hevc"),
            MediaStream(1, MediaStreamType.AUDIO, "dts"),
            MediaStream(2, MediaStreamType.SUBTITLE, "ass"),
        )

        val plan = MediaRemuxPlanner.plan(media, "mov", OutputContainer.MKV)

        assertEquals(PlanDisposition.READY, plan.disposition)
        assertEquals(listOf(0, 1, 2), plan.streamsToCopy.map(MediaStream::index))
    }

    @Test
    fun skipsWhenTheDetectedContainerAlreadyMatchesTheTarget() {
        val media = media(
            MediaStream(0, MediaStreamType.VIDEO, "h264"),
            formatNames = setOf("mov", "mp4", "m4a"),
            majorBrand = "isom",
        )

        val plan = MediaRemuxPlanner.plan(media, "mp4", OutputContainer.MP4)

        assertEquals(PlanDisposition.SKIP_SAME_CONTAINER, plan.disposition)
    }

    @Test
    fun skipsMpegTransportStreamAliases() {
        val media = media(
            MediaStream(0, MediaStreamType.VIDEO, "h264"),
            formatNames = setOf("mpegts"),
        )

        val plan = MediaRemuxPlanner.plan(media, "m2ts", OutputContainer.TS)

        assertEquals(PlanDisposition.SKIP_SAME_CONTAINER, plan.disposition)
    }

    @Test
    fun rejectsLosingAllAudioUnlessTheExplicitOptionIsEnabled() {
        val media = media(MediaStream(0, MediaStreamType.VIDEO, "h264"), MediaStream(1, MediaStreamType.AUDIO, "dts"))
        val rejected = MediaRemuxPlanner.plan(media, "mkv", OutputContainer.MP4)
        val allowed = MediaRemuxPlanner.plan(media, "mkv", OutputContainer.MP4, allowDropAllAudio = true)

        assertEquals(PlanRejection.AUDIO_UNSUPPORTED, rejected.rejection)
        assertEquals(PlanDisposition.READY_WITH_WARNINGS, allowed.disposition)
        assertTrue(allowed.warnings.any { it.stream.type == MediaStreamType.AUDIO })
    }

    @Test
    fun matroskaAttemptsUncommonCodecsAndKeepsCoverArt() {
        val media = media(
            MediaStream(0, MediaStreamType.VIDEO, "flv1"),
            MediaStream(1, MediaStreamType.AUDIO, "ape"),
            MediaStream(2, MediaStreamType.VIDEO, "mjpeg", attachedPicture = true),
        )
        val plan = MediaRemuxPlanner.plan(media, "flv", OutputContainer.MKV)

        assertEquals(listOf(0, 1, 2), plan.streamsToCopy.map(MediaStream::index))
    }

    @Test
    fun missingSubtitleEncoderDropsTextSubtitleWithWarning() {
        val media = media(
            MediaStream(0, MediaStreamType.VIDEO, "h264"),
            MediaStream(1, MediaStreamType.AUDIO, "aac"),
            MediaStream(2, MediaStreamType.SUBTITLE, "subrip"),
        )
        val plan = MediaRemuxPlanner.plan(media, "mkv", OutputContainer.MP4, availableSubtitleEncoders = emptySet())

        assertTrue(plan.streamsToCopy.none { it.type == MediaStreamType.SUBTITLE })
        assertEquals("subtitle_encoder_missing", plan.warnings.single().detail)
    }

    @Test
    fun convertsMovTextToSrtForMatroskaAndTextToWebVtt() {
        val mkvPlan = MediaRemuxPlanner.plan(
            media(MediaStream(0, MediaStreamType.VIDEO, "h264"), MediaStream(1, MediaStreamType.SUBTITLE, "mov_text")),
            "mp4",
            OutputContainer.MKV,
        )
        val webmPlan = MediaRemuxPlanner.plan(
            media(MediaStream(0, MediaStreamType.VIDEO, "vp9"), MediaStream(1, MediaStreamType.SUBTITLE, "ass")),
            "mkv",
            OutputContainer.WEBM,
        )

        assertEquals(listOf("-c:s:0", "srt"), mkvPlan.ffmpegSubtitleArguments())
        assertEquals(listOf("-c:s:0", "webvtt"), webmPlan.ffmpegSubtitleArguments())
    }

    @Test
    fun hevcTagIsAppliedOnlyToHevcVideoStreamsForMovContainers() {
        val plan = MediaRemuxPlanner.plan(
            media(
                MediaStream(0, MediaStreamType.VIDEO, "h264"),
                MediaStream(1, MediaStreamType.AUDIO, "aac"),
                MediaStream(2, MediaStreamType.VIDEO, "hevc"),
            ),
            "mkv",
            OutputContainer.MP4,
        )

        assertEquals(listOf("-tag:v:1", "hvc1"), plan.ffmpegVideoTagArguments(OutputContainer.MP4))
        assertTrue(plan.ffmpegVideoTagArguments(OutputContainer.MKV).isEmpty())
    }

    private fun media(
        vararg streams: MediaStream,
        formatNames: Set<String> = emptySet(),
        majorBrand: String? = null,
    ) = ProbedMedia(
        formatNames = formatNames,
        majorBrand = majorBrand,
        durationSeconds = 42.0,
        streams = streams.toList(),
    )
}
