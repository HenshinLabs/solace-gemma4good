package com.masterllm.runtime.gguf

import java.io.File

/**
 * Lightweight process-level CPU/GPU usage sampling utility.
 *
 * CPU usage is computed from /proc/self/stat and /proc/stat snapshots.
 * GPU usage attempts Qualcomm KGSL counters first, then falls back to
 * driver-provided instantaneous utilization values when available.
 */
object PerformanceUsageSampler {

    data class Snapshot(
        val processCpuTicks: Long,
        val totalCpuTicks: Long,
        val gpuBusyTicks: Long?,
        val gpuTotalTicks: Long?,
        val gpuInstantPercent: Double?,
    )

    data class UsagePercent(
        val cpuPercent: Double,
        val gpuPercent: Double?,
    )

    fun captureSnapshot(): Snapshot {
        val processCpuTicks = readProcessCpuTicks()
        val totalCpuTicks = readTotalCpuTicks()
        val kgsl = readKgslBusyCounters()
        val instantGpu = if (kgsl == null) readInstantGpuPercent() else null
        return Snapshot(
            processCpuTicks = processCpuTicks,
            totalCpuTicks = totalCpuTicks,
            gpuBusyTicks = kgsl?.first,
            gpuTotalTicks = kgsl?.second,
            gpuInstantPercent = instantGpu,
        )
    }

    fun computeUsage(start: Snapshot, end: Snapshot): UsagePercent {
        val processDelta = (end.processCpuTicks - start.processCpuTicks).coerceAtLeast(0L)
        val totalDelta = (end.totalCpuTicks - start.totalCpuTicks).coerceAtLeast(1L)

        val rawCpu = processDelta.toDouble() / totalDelta.toDouble() * 100.0
        val cpuPercent = rawCpu.coerceIn(0.0, 100.0)

        val gpuPercent = when {
            start.gpuBusyTicks != null &&
                start.gpuTotalTicks != null &&
                end.gpuBusyTicks != null &&
                end.gpuTotalTicks != null -> {
                val busyDelta = (end.gpuBusyTicks - start.gpuBusyTicks).coerceAtLeast(0L)
                val totalGpuDelta = (end.gpuTotalTicks - start.gpuTotalTicks).coerceAtLeast(0L)
                if (totalGpuDelta <= 0L) {
                    null
                } else {
                    (busyDelta.toDouble() / totalGpuDelta.toDouble() * 100.0).coerceIn(0.0, 100.0)
                }
            }

            start.gpuInstantPercent != null && end.gpuInstantPercent != null -> {
                ((start.gpuInstantPercent + end.gpuInstantPercent) / 2.0).coerceIn(0.0, 100.0)
            }

            else -> end.gpuInstantPercent?.coerceIn(0.0, 100.0)
        }

        return UsagePercent(
            cpuPercent = cpuPercent,
            gpuPercent = gpuPercent,
        )
    }

    private fun readProcessCpuTicks(): Long {
        return runCatching {
            // /proc/self/stat fields: utime(14), stime(15), cutime(16), cstime(17)
            val fields = File("/proc/self/stat").readText().trim().split(" ")
            if (fields.size < 17) return@runCatching 0L
            val utime = fields[13].toLongOrNull() ?: 0L
            val stime = fields[14].toLongOrNull() ?: 0L
            val cutime = fields[15].toLongOrNull() ?: 0L
            val cstime = fields[16].toLongOrNull() ?: 0L
            utime + stime + cutime + cstime
        }.getOrDefault(0L)
    }

    private fun readTotalCpuTicks(): Long {
        return runCatching {
            val cpuLine = File("/proc/stat").useLines { lines ->
                lines.firstOrNull { it.startsWith("cpu ") } ?: return@useLines ""
            }
            val parts = cpuLine.trim().split(Regex("\\s+"))
            if (parts.size <= 1) return@runCatching 0L
            parts.drop(1).sumOf { it.toLongOrNull() ?: 0L }
        }.getOrDefault(0L)
    }

    private fun readKgslBusyCounters(): Pair<Long, Long>? {
        return runCatching {
            val gpubusyPath = File("/sys/class/kgsl/kgsl-3d0/gpubusy")
            if (!gpubusyPath.exists()) return@runCatching null
            val tokens = gpubusyPath.readText().trim().split(Regex("\\s+"))
            if (tokens.size < 2) return@runCatching null
            val busy = tokens[0].toLongOrNull() ?: return@runCatching null
            val total = tokens[1].toLongOrNull() ?: return@runCatching null
            busy to total
        }.getOrNull()
    }

    private fun readInstantGpuPercent(): Double? {
        val candidatePaths = listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/misc/mali0/device/utilization",
        )

        for (path in candidatePaths) {
            val value = runCatching {
                val file = File(path)
                if (!file.exists()) return@runCatching null
                val raw = file.readText().trim()
                raw.toDoubleOrNull()
            }.getOrNull()

            if (value != null) return value
        }
        return null
    }
}
