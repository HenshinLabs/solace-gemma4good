package com.masterllm.core.ollama.di

import com.masterllm.core.ollama.api.OllamaApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object OllamaModule {
    @Provides
    @Singleton
    fun provideOllamaApiService(): OllamaApiService = OllamaApiService()
}
