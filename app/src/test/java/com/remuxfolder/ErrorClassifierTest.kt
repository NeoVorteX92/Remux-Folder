package com.remuxfolder

import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorClassifierTest {
    @Test fun classifiesFullDisk() = assertEquals(RemuxErrorCode.SIN_ESPACIO, ErrorClassifier.classify("No space left on device"))
    @Test fun classifiesUnsupportedStreamContainerPair() = assertEquals(
        RemuxErrorCode.STREAM_INCOMPATIBLE,
        ErrorClassifier.classify("Could not find tag for codec h264 in stream #0:0, codec not currently supported in container"),
    )
    @Test fun classifiesInvalidInput() = assertEquals(
        RemuxErrorCode.ENTRADA_INVALIDA,
        ErrorClassifier.classify("Invalid data found when processing input"),
    )
    @Test fun classifiesPermissionFailure() = assertEquals(RemuxErrorCode.PERMISO, ErrorClassifier.classify("Permission denied"))
    @Test fun classifiesUnknown() = assertEquals(RemuxErrorCode.DESCONOCIDO, ErrorClassifier.classify("unexpected failure"))
}
