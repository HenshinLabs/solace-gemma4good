package com.masterllm.core.data.di

import android.content.Context
import androidx.room.Room
import com.masterllm.core.data.db.*
import com.masterllm.core.data.repository.*
import com.masterllm.core.domain.repository.*
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "master_llm.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideModelDao(db: AppDatabase): ModelDao = db.modelDao()

    @Provides
    fun provideConversationDao(db: AppDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideRoleplaySessionDao(db: AppDatabase): RoleplaySessionDao = db.roleplaySessionDao()

    @Provides
    fun provideCharacterVisualCacheDao(db: AppDatabase): CharacterVisualCacheDao = db.characterVisualCacheDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindModelRepository(impl: ModelRepositoryImpl): ModelRepository

    @Binds
    @Singleton
    abstract fun bindConversationRepository(impl: ConversationRepositoryImpl): ConversationRepository

    @Binds
    @Singleton
    abstract fun bindRoleplayRepository(impl: RoleplayRepositoryImpl): RoleplayRepository

    @Binds
    @Singleton
    abstract fun bindCharacterVisualCacheRepository(impl: CharacterVisualCacheRepositoryImpl): CharacterVisualCacheRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}
