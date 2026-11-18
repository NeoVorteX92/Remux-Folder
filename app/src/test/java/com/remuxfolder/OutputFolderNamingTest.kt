package com.remuxfolder

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputFolderNamingTest {
    @Test fun usesReadableNameInsideParent() = assertEquals("Vacaciones (remux)", OutputFolderNaming.current("Vacaciones"))
    @Test fun returnsLegacyNameForExistingOutputCompatibility() = assertEquals("Vacacionesremux", OutputFolderNaming.legacy("Vacaciones"))
    @Test fun fallsBackWithoutSpanishFolderLabel() = assertEquals("Videos (remux)", OutputFolderNaming.current(null))
    @Test fun makesSafeOutputNamesAndKeepsUnicode() {
        assertEquals("café 🎞️_clip.mp4", FileNamePolicy.outputName("café 🎞️/clip.mov", "mp4"))
    }
    @Test fun fallsBackForAnEmptyOrDotOnlyName() = assertEquals("video.mkv", FileNamePolicy.outputName("...", "mkv"))
}
