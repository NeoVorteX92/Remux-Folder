package com.remuxfolder

import java.util.Locale

enum class RemuxErrorCode {
    SIN_ESPACIO,
    STREAM_INCOMPATIBLE,
    ENTRADA_INVALIDA,
    PERMISO,
    DESCONOCIDO,
}

/** Pure log classifier so the mapping can be checked without Android or FFmpeg. */
object ErrorClassifier {
    fun classify(log: String): RemuxErrorCode {
        val value = log.lowercase(Locale.ROOT)
        return when {
            "no space left on device" in value -> RemuxErrorCode.SIN_ESPACIO
            "could not find tag for codec" in value &&
                ("not currently supported in container" in value || "codec not currently supported" in value) ->
                RemuxErrorCode.STREAM_INCOMPATIBLE
            "invalid data found when processing input" in value -> RemuxErrorCode.ENTRADA_INVALIDA
            "permission denied" in value -> RemuxErrorCode.PERMISO
            else -> RemuxErrorCode.DESCONOCIDO
        }
    }
}
