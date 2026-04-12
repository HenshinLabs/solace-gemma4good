plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.masterllm.testing.fixtures"
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
    implementation(project(":core-data"))

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.testing)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.test)

    implementation(libs.junit5.api)
    implementation(libs.truth)
}
