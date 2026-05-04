package com.masterllm.feature.performance

import androidx.lifecycle.ViewModel
import com.masterllm.core.domain.repository.SettingsRepository
import com.masterllm.runtime.gguf.InferencePerformanceTracker
import com.masterllm.runtime.gguf.LlmInferenceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class PerformanceMonitorViewModel @Inject constructor(
    private val inferenceManager: LlmInferenceManager,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val liveStats: StateFlow<InferencePerformanceTracker.LiveStats> = inferenceManager.liveStats

    val isModelLoaded: Boolean
        get() = inferenceManager.isModelLoaded.get()

    val architectureInfo: String
        get() = inferenceManager.getArchitectureInfo()

    val optimalThreads: Int
        get() = inferenceManager.getOptimalThreadCount()

    val libraryName: String
        get() = inferenceManager.getLoadedLibraryName()
}
