package com.masterllm.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.masterllm.core.domain.model.ImageFrequency
import com.masterllm.core.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private object Keys {
        val HF_TOKEN = stringPreferencesKey("hf_token")
        val HF_USERNAME = stringPreferencesKey("hf_username")
        val AUTO_COMPACTION_THRESHOLD = intPreferencesKey("auto_compaction_threshold")
        val DEFAULT_THREAD_COUNT = intPreferencesKey("default_thread_count")
        val THEME = stringPreferencesKey("theme")
        val DEFAULT_IMAGE_FREQUENCY = stringPreferencesKey("default_image_frequency")
        val CHARACTER_CONSISTENCY = booleanPreferencesKey("character_consistency")
        val GPU_ACCELERATION = booleanPreferencesKey("gpu_acceleration")
        val MODEL_STORAGE_PATH = stringPreferencesKey("model_storage_path")
    }

    // ─── HF Token ──────────────────────────────────────────────

    override fun getHfToken(): Flow<String> =
        context.dataStore.data.map { it[Keys.HF_TOKEN] ?: "" }

    override suspend fun setHfToken(token: String) {
        context.dataStore.edit { it[Keys.HF_TOKEN] = token }
    }

    // ─── HF Username ─────────────────────────────────────────

    override fun getHfUsername(): Flow<String> =
        context.dataStore.data.map { it[Keys.HF_USERNAME] ?: "" }

    override suspend fun setHfUsername(username: String) {
        context.dataStore.edit { it[Keys.HF_USERNAME] = username }
    }

    // ─── Auto Compaction ──────────────────────────────────────

    override fun getAutoCompactionThreshold(): Flow<Int> =
        context.dataStore.data.map { it[Keys.AUTO_COMPACTION_THRESHOLD] ?: 80 }

    override suspend fun setAutoCompactionThreshold(percent: Int) {
        context.dataStore.edit { it[Keys.AUTO_COMPACTION_THRESHOLD] = percent }
    }

    // ─── Thread Count ─────────────────────────────────────────

    override fun getDefaultThreadCount(): Flow<Int> =
        context.dataStore.data.map { it[Keys.DEFAULT_THREAD_COUNT] ?: 4 }

    override suspend fun setDefaultThreadCount(count: Int) {
        context.dataStore.edit { it[Keys.DEFAULT_THREAD_COUNT] = count }
    }

    // ─── Theme ────────────────────────────────────────────────

    override fun getTheme(): Flow<String> =
        context.dataStore.data.map { it[Keys.THEME] ?: "system" }

    override suspend fun setTheme(theme: String) {
        context.dataStore.edit { it[Keys.THEME] = theme }
    }

    // ─── Image Frequency ──────────────────────────────────────

    override fun getDefaultImageFrequency(): Flow<ImageFrequency> =
        context.dataStore.data.map { prefs ->
            try {
                ImageFrequency.valueOf(prefs[Keys.DEFAULT_IMAGE_FREQUENCY] ?: "EVERY_RESPONSE")
            } catch (_: Exception) {
                ImageFrequency.EVERY_RESPONSE
            }
        }

    override suspend fun setDefaultImageFrequency(freq: ImageFrequency) {
        context.dataStore.edit { it[Keys.DEFAULT_IMAGE_FREQUENCY] = freq.name }
    }

    // ─── Character Consistency ────────────────────────────────

    override fun getCharacterConsistencyEnabled(): Flow<Boolean> =
        context.dataStore.data.map { it[Keys.CHARACTER_CONSISTENCY] ?: true }

    override suspend fun setCharacterConsistencyEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CHARACTER_CONSISTENCY] = enabled }
    }

    // ─── GPU Acceleration ─────────────────────────────────────

    override fun getGpuAccelerationEnabled(): Flow<Boolean> =
        context.dataStore.data.map { it[Keys.GPU_ACCELERATION] ?: false }

    override suspend fun setGpuAccelerationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.GPU_ACCELERATION] = enabled }
    }

    // ─── Model Storage Path ───────────────────────────────────

    override fun getModelStoragePath(): Flow<String> =
        context.dataStore.data.map { it[Keys.MODEL_STORAGE_PATH] ?: "" }

    override suspend fun setModelStoragePath(path: String) {
        context.dataStore.edit { it[Keys.MODEL_STORAGE_PATH] = path }
    }
}
