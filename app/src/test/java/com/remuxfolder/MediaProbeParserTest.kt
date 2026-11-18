package com.remuxfolder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProbeParserTest {
    @Test fun parsesStreamsAndAttachedCover() {
        val media = MediaProbeParser.parse(
            """{"format":{"format_name":"mov,mp4,m4a","duration":"12.5","tags":{"major_brand":"isom"}},"streams":[{"index":0,"codec_type":"video","codec_name":"mjpeg","disposition":{"attached_pic":1}},{"index":1,"codec_type":"video","codec_name":"h264","disposition":{"attached_pic":0}},{"index":2,"codec_type":"audio","codec_name":"aac"}]}""",
        )
        assertEquals(12.5, media.durationSeconds!!, 0.001)
        assertTrue("mp4" in media.formatNames)
        assertTrue(media.streams.first().attachedPicture)
        assertEquals(MediaStreamType.AUDIO, media.streams.last().type)
    }
}
