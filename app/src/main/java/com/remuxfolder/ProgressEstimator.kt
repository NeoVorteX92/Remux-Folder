package com.remuxfolder

import kotlin.math.max

data class ProgressEstimate(val percent: Int, val speed: String, val etaSeconds: Long?)

object ProgressEstimator {
    fun estimate(timeMillis: Long, durationSeconds: Double?, speed: String): ProgressEstimate {
        val durationMillis = durationSeconds?.takeIf { it > 0.0 }?.times(1_000.0)
        val percent = if (durationMillis == null) 0 else
            ((timeMillis.coerceAtLeast(0) * 100.0 / durationMillis).toInt()).coerceIn(0, 99)
        val rate = speed.removeSuffix("x").toDoubleOrNull()?.takeIf { it > 0.0 }
        val remainingMediaSeconds = durationSeconds?.let { max(0.0, it - timeMillis.coerceAtLeast(0) / 1_000.0) }
        val eta = if (rate == null || remainingMediaSeconds == null) null else (remainingMediaSeconds / rate).toLong()
        return ProgressEstimate(percent, speed, eta)
    }
}
