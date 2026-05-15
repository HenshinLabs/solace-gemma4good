package com.masterllm.app.solace

import android.content.Context
import com.masterllm.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemPromptLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var cachedPrompt: String? = null

    fun getSystemPrompt(): String {
        cachedPrompt?.let { return it }

        val prompt = context.resources.openRawResource(R.raw.system_prompt)
            .bufferedReader()
            .use { it.readText() }
            .trim()

        cachedPrompt = prompt
        return prompt
    }
}
