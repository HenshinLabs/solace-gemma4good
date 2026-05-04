package com.masterllm.app.navigation

import androidx.lifecycle.ViewModel
import com.masterllm.runtime.gguf.GgufEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class GgufEngineViewModel @Inject constructor(
    val engine: GgufEngine,
) : ViewModel()
