package com.masterllm.runtime.gguf

import android.content.Context
import android.os.Debug
import java.io.File

class InferencePerformanceTracker(private val context: Context) {

    data class LiveStats(
        val tokensPerSecond: Float,
        val tokensGenerated: Int,
        val contextUsed: Int,
        val contextMax: Int,
        val memoryUsedMB: Int,
        val memoryMaxMB: Int,
        val cpuUsagePercent: Float,
        val gpuUsagePercent: Float?,
        val cpuFreqMHz: Map<Int, Long>,
        val gpuFreqMHz: Long?,
        val thermalZoneCelsius: Map<String, Float>,
        val firstTokenLatencyMs: Long,
        val elapsedMs: Long,
    )

    @Volatile
    private var tokensGenerated = 0

    @Volatile
    private var _contextUsed = 0

    @Volatile
    private var _contextMax = 0

    @Volatile
    private var firstTokenAtNs: Long? = null

    @Volatile
    private var tracking = false

    private var startedAtNs = 0L
    private var lastSnapshotAtNs = 0L
    private var prevProcessTicks = 0L
    private var prevSystemTicks = 0L
    private var prevCpuSnapshotNs = 0L
    private val cpuCount: Int = Runtime.getRuntime().availableProcessors()
    private val cpuFreqBasePaths = (0 until cpuCount).map { i ->
        "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq"
    }

    private val thermalZonePaths: List<Pair<String, Long>> by lazy {
        discoverThermalZones()
    }

    fun startTracking() {
        tokensGenerated = 0
        _contextUsed = 0
        _contextMax = 0
        firstTokenAtNs = null
        tracking = true
        startedAtNs = System.nanoTime()
        lastSnapshotAtNs = startedAtNs
    }

    fun recordToken() {
        tokensGenerated += 1
        if (firstTokenAtNs == null) {
            firstTokenAtNs = System.nanoTime()
        }
    }

    fun updateContextUsage(used: Int, max: Int) {
        _contextUsed = used
        _contextMax = max
    }

    fun getSnapshot(): LiveStats {
        val nowNs = System.nanoTime()
        lastSnapshotAtNs = nowNs

        val generated = tokensGenerated
        val start = startedAtNs
        val firstTok = firstTokenAtNs
        val elapsedMs = ((nowNs - start) / 1_000_000L).coerceAtLeast(0L)
        val firstTokenLatencyMs = firstTok
            ?.let { ((it - start) / 1_000_000L).coerceAtLeast(0L) }
            ?: elapsedMs

        val tps = if (elapsedMs > 0) {
            generated.toFloat() / (elapsedMs / 1000f)
        } else {
            0f
        }

        val memoryUsedMB = (Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)).toInt()
        val runtime = Runtime.getRuntime()
        val javaUsedMB = ((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)).toInt()
        val totalUsedMB = memoryUsedMB + javaUsedMB
        val memoryMaxMB = (runtime.maxMemory() / (1024L * 1024L)).toInt()

        val cpuUsagePercent = readProcessCpuUsage()

        val gpuFreqMHz = readGpuFrequency()
        val gpuUsagePercent = readGpuUtilization()

        val cpuFreqMHz = readCpuFrequencies()
        val thermalCelsius = readThermalZones()

        return LiveStats(
            tokensPerSecond = tps,
            tokensGenerated = generated,
            contextUsed = _contextUsed,
            contextMax = _contextMax,
            memoryUsedMB = totalUsedMB,
            memoryMaxMB = memoryMaxMB,
            cpuUsagePercent = cpuUsagePercent,
            gpuUsagePercent = gpuUsagePercent,
            cpuFreqMHz = cpuFreqMHz,
            gpuFreqMHz = gpuFreqMHz,
            thermalZoneCelsius = thermalCelsius,
            firstTokenLatencyMs = firstTokenLatencyMs,
            elapsedMs = elapsedMs,
        )
    }

    fun stopTracking(): LiveStats {
        tracking = false
        return getSnapshot()
    }

    private fun readProcessCpuUsage(): Float {
        return try {
            val procStat = File("/proc/self/stat").readText().trim().split(Regex("\\s+"))
            val totalStat = File("/proc/stat").readText().lines()
                .firstOrNull { it.startsWith("cpu ") }
                ?.trim()
                ?.split(Regex("\\s+"))
            if (procStat.size < 17 || totalStat == null || totalStat.size <= 1) return 0f

            val utime = procStat[13].toLongOrNull() ?: 0L
            val stime = procStat[14].toLongOrNull() ?: 0L
            val processTicks = utime + stime

            val systemTicks = totalStat.drop(1).sumOf { it.toLongOrNull() ?: 0L }
            if (systemTicks <= 0L) return 0f

            val nowNs = System.nanoTime()
            if (prevCpuSnapshotNs == 0L || prevSystemTicks == 0L) {
                prevProcessTicks = processTicks
                prevSystemTicks = systemTicks
                prevCpuSnapshotNs = nowNs
                return 0f
            }

            val processDelta = (processTicks - prevProcessTicks).coerceAtLeast(0L)
            val systemDelta = (systemTicks - prevSystemTicks).coerceAtLeast(1L)

            prevProcessTicks = processTicks
            prevSystemTicks = systemTicks
            prevCpuSnapshotNs = nowNs

            (processDelta.toFloat() / systemDelta.toFloat() * 100f).coerceIn(0f, 100f)
        } catch (e: Exception) {
            0f
        }
    }

    private fun readCpuFrequencies(): Map<Int, Long> {
        val result = mutableMapOf<Int, Long>()
        for ((index, path) in cpuFreqBasePaths.withIndex()) {
            try {
                val file = File(path)
                if (file.exists()) {
                    val khz = file.readText().trim().toLongOrNull()
                    if (khz != null && khz > 0) {
                        result[index] = khz / 1000L
                    }
                }
            } catch (_: Exception) {
            }
        }
        return result
    }

    private fun readGpuFrequency(): Long? {
        val candidatePaths = listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/devfreq/1c00000.qcom,kgsl-3d0/cur_freq",
            "/sys/class/misc/mali0/device/devfreq/ff9a0000.gpu/cur_freq",
            "/sys/kernel/gpu/gpu_clock",
        )
        for (path in candidatePaths) {
            try {
                val file = File(path)
                if (file.exists()) {
                    val value = file.readText().trim().toLongOrNull()
                    if (value != null && value > 0) {
                        return if (value > 10_000_000L) value / 1_000_000L else value
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun readGpuUtilization(): Float? {
        val candidatePaths = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/kernel/gpu/gpu_busy",
        )
        for (path in candidatePaths) {
            try {
                val file = File(path)
                if (!file.exists()) continue
                val raw = file.readText().trim()

                if (raw.contains(" ")) {
                    val parts = raw.split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        val busy = parts[0].toLongOrNull() ?: continue
                        val total = parts[1].toLongOrNull() ?: continue
                        if (total > 0) return (busy.toFloat() / total.toFloat() * 100f).coerceIn(0f, 100f)
                    }
                } else {
                    val pct = raw.toFloatOrNull()
                    if (pct != null) return pct.coerceIn(0f, 100f)
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun discoverThermalZones(): List<Pair<String, Long>> {
        val zones = mutableListOf<Pair<String, Long>>()
        try {
            val baseDir = File("/sys/class/thermal")
            if (!baseDir.isDirectory) return zones
            val zoneDirs = baseDir.listFiles { file -> file.isDirectory && file.name.startsWith("thermal_zone") }
                ?: return zones

            for (zoneDir in zoneDirs) {
                try {
                    val typeFile = File(zoneDir, "type")
                    if (!typeFile.exists()) continue
                    val type = typeFile.readText().trim()

                    val tempFile = File(zoneDir, "temp")
                    if (!tempFile.exists()) continue
                    val tempRaw = tempFile.readText().trim().toLongOrNull() ?: continue

                    zones += type to tempRaw
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return zones
    }

    private fun readThermalZones(): Map<String, Float> {
        val result = mutableMapOf<String, Float>()
        val baseDir = File("/sys/class/thermal")
        if (!baseDir.isDirectory) return result
        val zoneDirs = baseDir.listFiles { file -> file.isDirectory && file.name.startsWith("thermal_zone") } ?: return result
        for (zoneDir in zoneDirs) {
            try {
                val typeFile = File(zoneDir, "type")
                if (!typeFile.exists()) continue
                val type = typeFile.readText().trim()
                val tempFile = File(zoneDir, "temp")
                if (!tempFile.exists()) continue
                val millicelsius = tempFile.readText().trim().toLongOrNull() ?: continue
                val celsius = millicelsius / 1000f
                if (celsius > 0f) result[type] = celsius
            } catch (_: Exception) {}
        }
        return result
    }
}
