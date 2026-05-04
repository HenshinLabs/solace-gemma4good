package com.masterllm.feature.performance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Token
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masterllm.runtime.gguf.InferencePerformanceTracker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceMonitorScreen(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    viewModel: PerformanceMonitorViewModel = hiltViewModel(),
) {
    val stats by viewModel.liveStats.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Performance Monitor") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        if (!viewModel.isModelLoaded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "No model loaded",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Load a model to see real-time performance metrics",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "tps") { TpsGaugeCard(stats) }
                item(key = "gen") { GenerationStatsCard(stats) }
                item(key = "cpu") { CpuMonitorCard(stats) }
                item(key = "gpu") { GpuMonitorCard(stats) }
                item(key = "mem") { MemoryCard(stats) }
                item(key = "thermal") { ThermalZonesCard(stats) }
                item(key = "arch") { ArchitectureInfoCard(viewModel) }
            }
        }
    }
}

// ─── TPS Gauge Card ────────────────────────────────────────────────────────────

@Composable
private fun TpsGaugeCard(stats: InferencePerformanceTracker.LiveStats) {
    val animatedTps by animateFloatAsState(
        targetValue = stats.tokensPerSecond,
        animationSpec = tween(durationMillis = 400),
        label = "tps",
    )

    val isGenerating = stats.tokensPerSecond > 0f || stats.tokensGenerated > 0

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Tokens Per Second",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            TpsArcGauge(
                value = animatedTps,
                modifier = Modifier.size(180.dp),
                strokeWidth = 14.dp,
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = String.format("%.1f", animatedTps),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "tokens/s",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AnimatedVisibility(
                visible = !isGenerating && stats.tokensGenerated == 0,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Text(
                    text = "Waiting for generation...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TpsArcGauge(
    value: Float,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 12.dp,
) {
    val maxDisplay = 120f
    val progress = (value / maxDisplay).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 600),
        label = "arcProgress",
    )

    val gaugeColor = when {
        value < 10f -> MaterialTheme.colorScheme.error
        value < 30f -> Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.primary
    }

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val sweepAngle = animatedProgress * 270f

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val arcSize = size.minDimension - strokeWidth.toPx()
            val topLeft = Offset(strokeWidth.toPx() / 2f, strokeWidth.toPx() / 2f)
            val arcBounds = Size(arcSize, arcSize)

            drawArc(
                color = trackColor,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcBounds,
                style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round),
            )

            drawArc(
                color = gaugeColor,
                startAngle = 135f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcBounds,
                style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Speed,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = gaugeColor,
            )
        }
    }
}

// ─── Generation Stats Card ─────────────────────────────────────────────────────

@Composable
private fun GenerationStatsCard(stats: InferencePerformanceTracker.LiveStats) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Token,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Generation Stats",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(16.dp))

            StatRow("Tokens generated", formatNumber(stats.tokensGenerated.toLong()))
            Spacer(Modifier.height(10.dp))

            if (stats.contextMax > 0) {
                val contextPct = if (stats.contextMax > 0) {
                    (stats.contextUsed.toFloat() / stats.contextMax.toFloat()).coerceIn(0f, 1f)
                } else 0f

                val animatedContextPct by animateFloatAsState(
                    targetValue = contextPct,
                    animationSpec = tween(durationMillis = 500),
                    label = "contextPct",
                )

                StatRow(
                    "Context usage",
                    "${formatNumber(stats.contextUsed.toLong())} / ${formatNumber(stats.contextMax.toLong())}",
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { animatedContextPct },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = when {
                        contextPct > 0.9f -> MaterialTheme.colorScheme.error
                        contextPct > 0.7f -> Color(0xFFFF9800)
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
            }

            StatRow("Total time", formatDuration(stats.elapsedMs))
            Spacer(Modifier.height(10.dp))
            StatRow("First token latency", formatDuration(stats.firstTokenLatencyMs))
        }
    }
}

// ─── CPU Monitor Card ──────────────────────────────────────────────────────────

@Composable
private fun CpuMonitorCard(stats: InferencePerformanceTracker.LiveStats) {
    val animatedCpu by animateFloatAsState(
        targetValue = stats.cpuUsagePercent,
        animationSpec = tween(durationMillis = 500),
        label = "cpu",
    )

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Computer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "CPU",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = String.format("%.1f%%", animatedCpu),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = when {
                        animatedCpu > 90f -> MaterialTheme.colorScheme.error
                        animatedCpu > 70f -> Color(0xFFFF9800)
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
                Text(
                    text = "utilization",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { animatedCpu / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = when {
                    animatedCpu > 90f -> MaterialTheme.colorScheme.error
                    animatedCpu > 70f -> Color(0xFFFF9800)
                    else -> MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            if (stats.cpuFreqMHz.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Per-core frequency",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                val maxFreq = stats.cpuFreqMHz.values.maxOrNull() ?: 1L
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    stats.cpuFreqMHz.entries
                        .sortedBy { it.key }
                        .forEach { (core, freqMhz) ->
                            CpuCoreRow(core = core, freqMhz = freqMhz, maxFreq = maxFreq)
                        }
                }
            }
        }
    }
}

@Composable
private fun CpuCoreRow(core: Int, freqMhz: Long, maxFreq: Long) {
    val fraction = if (maxFreq > 0) (freqMhz.toFloat() / maxFreq.toFloat()).coerceIn(0f, 1f) else 0f
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 500),
        label = "cpuCore$core",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Core $core",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(48.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFraction)
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary,
                            )
                        )
                    ),
            )
        }
        Text(
            text = "${freqMhz} MHz",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ─── GPU Monitor Card ──────────────────────────────────────────────────────────

@Composable
private fun GpuMonitorCard(stats: InferencePerformanceTracker.LiveStats) {
    val gpuUsage = stats.gpuUsagePercent
    val animatedGpu by animateFloatAsState(
        targetValue = gpuUsage ?: 0f,
        animationSpec = tween(durationMillis = 500),
        label = "gpu",
    )

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.DeveloperBoard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "GPU",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(16.dp))

            if (gpuUsage != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = String.format("%.1f%%", animatedGpu),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = when {
                            animatedGpu > 90f -> MaterialTheme.colorScheme.error
                            animatedGpu > 70f -> Color(0xFFFF9800)
                            else -> MaterialTheme.colorScheme.primary
                        },
                    )
                    Text(
                        text = "utilization",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { animatedGpu / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = when {
                        animatedGpu > 90f -> MaterialTheme.colorScheme.error
                        animatedGpu > 70f -> Color(0xFFFF9800)
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            } else {
                Text(
                    text = "GPU not detected",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }

            if (stats.gpuFreqMHz != null) {
                Spacer(Modifier.height(12.dp))
                StatRow("GPU frequency", "${stats.gpuFreqMHz} MHz")
            }
        }
    }
}

// ─── Memory Card ───────────────────────────────────────────────────────────────

@Composable
private fun MemoryCard(stats: InferencePerformanceTracker.LiveStats) {
    val memoryPct = if (stats.memoryMaxMB > 0) {
        (stats.memoryUsedMB.toFloat() / stats.memoryMaxMB.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val animatedMemoryPct by animateFloatAsState(
        targetValue = memoryPct,
        animationSpec = tween(durationMillis = 500),
        label = "memoryPct",
    )

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SdStorage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Memory",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = "${stats.memoryUsedMB} MB",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "of ${stats.memoryMaxMB} MB",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { animatedMemoryPct },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                color = when {
                    memoryPct > 0.9f -> MaterialTheme.colorScheme.error
                    memoryPct > 0.7f -> Color(0xFFFF9800)
                    else -> MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = String.format("%.0f%% used", memoryPct * 100f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
            )
        }
    }
}

// ─── Thermal Zones Card ────────────────────────────────────────────────────────

@Composable
private fun ThermalZonesCard(stats: InferencePerformanceTracker.LiveStats) {
    val zones = stats.thermalZoneCelsius
    val visible by animateFloatAsState(
        targetValue = if (zones.isNotEmpty()) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "thermalVisibility",
    )

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Thermostat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Thermal Zones",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(16.dp))

            if (zones.isEmpty()) {
                Text(
                    text = "No thermal sensors available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    zones.entries
                        .sortedBy { it.key }
                        .forEach { (name, celsius) ->
                            ThermalZoneRow(name = name, celsius = celsius)
                        }
                }
            }
        }
    }
}

@Composable
private fun ThermalZoneRow(name: String, celsius: Float) {
    val animatedCelsius by animateFloatAsState(
        targetValue = celsius,
        animationSpec = tween(durationMillis = 500),
        label = "thermal_$name",
    )

    val gaugeFraction = (animatedCelsius / 100f).coerceIn(0f, 1f)
    val color = when {
        animatedCelsius > 70f -> Color(0xFFE53935)
        animatedCelsius > 50f -> Color(0xFFFF9800)
        else -> Color(0xFF4CAF50)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(72.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(gaugeFraction)
                    .fillMaxSize()
                    .background(color),
            )
        }

        Text(
            text = String.format("%.1f°C", animatedCelsius),
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            ),
            color = color,
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.End,
        )
    }
}

// ─── Architecture Info Card ────────────────────────────────────────────────────

@Composable
private fun ArchitectureInfoCard(viewModel: PerformanceMonitorViewModel) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Hub,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Architecture",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(16.dp))

            StatRow("CPU type", viewModel.architectureInfo)
            Spacer(Modifier.height(10.dp))
            StatRow("Native library", viewModel.libraryName)
            Spacer(Modifier.height(10.dp))
            StatRow("Optimal threads", viewModel.optimalThreads.toString())
        }
    }
}

// ─── Reusable Stat Row ─────────────────────────────────────────────────────────

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ─── Utility Functions ─────────────────────────────────────────────────────────

private fun formatNumber(value: Long): String {
    return when {
        value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format("%.1fK", value / 1_000.0)
        else -> value.toString()
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0ms"
    val seconds = ms / 1000
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    val remainingMs = ms % 1000
    return when {
        minutes > 0 -> "${minutes}m ${remainingSeconds}s"
        seconds > 0 -> "${seconds}.${remainingMs / 100}s"
        else -> "${ms}ms"
    }
}
