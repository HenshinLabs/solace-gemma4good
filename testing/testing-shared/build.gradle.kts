plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.masterllm.testing.shared"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core-domain"))

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.test)

    // Testing utilities
    implementation(libs.junit5.api)
    implementation(libs.mockk)
    implementation(libs.truth)
    implementation(libs.turbine)
}
