package com.remuxfolder

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

object FfmpegDiagnostics {
    private val diagnosticCommands = listOf(
        "-version" to "Versión y configuración",
        "-muxers" to "Muxers",
        "-demuxers" to "Demuxers",
        "-encoders" to "Encoders",
        "-bsfs" to "Filtros de bitstream",
        "-protocols" to "Protocolos",
    )

    suspend fun collect(): String {
        val report = StringBuilder()
        for ((argument, title) in diagnosticCommands) {
            val session = execute(listOf("-hide_banner", argument))
            report.appendLine("===== $title ($argument) =====")
            report.appendLine(session.allLogsAsString.ifBlank { "Sin salida de texto." })
            report.appendLine(if (ReturnCode.isSuccess(session.returnCode)) "Código de salida: 0" else "Código de salida: error")
            report.appendLine()
        }
        val rawReport = report.toString()
        report.appendLine("===== Comprobación de licencia =====")
        report.appendLine(if ("--enable-gpl" in rawReport) "ALERTA: aparece --enable-gpl" else "No aparece --enable-gpl en la configuración mostrada.")
        report.appendLine("MP4/MOV usan un temporal local para que faststart pueda mover sus índices; luego se copia al destino SAF.")
        report.appendLine("===== Encoders de subtítulos =====")
        val found = subtitleEncoders(rawReport)
        for (encoder in listOf("mov_text", "subrip/srt", "webvtt", "ass")) {
            val key = if (encoder == "subrip/srt") setOf("subrip", "srt") else setOf(encoder)
            report.appendLine("$encoder: ${if (found.any { it in key }) "disponible" else "no encontrado (se descartará con aviso)"}")
        }
        return report.toString()
    }

    suspend fun availableSubtitleEncoders(): Set<String> {
        val output = try {
            execute(listOf("-hide_banner", "-encoders")).allLogsAsString
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ""
        }
        val found = subtitleEncoders(output)
        return found
    }

    suspend fun availableBitstreamFilters(): Set<String> {
        val output = try {
            execute(listOf("-hide_banner", "-bsfs")).allLogsAsString
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ""
        }
        return Regex("(?m)^\\s*([A-Za-z0-9_]+)\\s*$")
            .findAll(output)
            .map { it.groupValues[1] }
            .toSet()
    }

    fun subtitleEncoders(output: String): Set<String> {
        val names = Regex("(?m)^\\s*[VASD.]{6}\\s+([A-Za-z0-9_]+)")
            .findAll(output)
            .map { it.groupValues[1].lowercase() }
            .toSet()
        val found = names.intersect(MediaRemuxPlanner.DEFAULT_SUBTITLE_ENCODERS).toMutableSet()
        if ("srt" in found || "subrip" in found) found += setOf("srt", "subrip")
        return found
    }

    private suspend fun execute(arguments: List<String>) = suspendCancellableCoroutine { continuation ->
        val session = FFmpegKit.executeWithArgumentsAsync(arguments.toTypedArray()) { completed ->
            if (continuation.isActive) continuation.resume(completed)
        }
        continuation.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
    }
}
