package com.masterllm.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.masterllm.core.data.BundledModelManager
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class MasterLLMApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // No longer extracting a bundled model from assets — Gemma 4 E2B is
        // downloaded at runtime via ModelDownloadManager.
        // BundledModelManager.initialize() still works but returns null when
        // the model hasn't been downloaded yet.
        BundledModelManager.initialize(this)

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            "solace_default",
            "Solace Notifications",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
