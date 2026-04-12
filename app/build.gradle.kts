import org.gradle.api.GradleException
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class VerifyTurnipAssetsTask : DefaultTask() {
    @get:Input
    abstract val requiredAssetPaths: ListProperty<String>

    @get:InputFiles
    abstract val requiredAssetFiles: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val paths = requiredAssetPaths.get()
        val files = requiredAssetFiles.files.toList()
        val missing = paths.zip(files)
            .filter { (_, file) -> !file.exists() || file.length() == 0L }
            .map { (path, _) -> path }

        if (missing.isNotEmpty()) {
            throw GradleException(
                "Turnip release bundle is incomplete. Missing assets: ${missing.joinToString()}"
            )
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.masterllm.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.masterllm.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseStoreFile = providers.gradleProperty("MASTER_LLM_RELEASE_STORE_FILE").orNull
        ?: System.getenv("MASTER_LLM_RELEASE_STORE_FILE")
    val releaseStorePassword = providers.gradleProperty("MASTER_LLM_RELEASE_STORE_PASSWORD").orNull
        ?: System.getenv("MASTER_LLM_RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = providers.gradleProperty("MASTER_LLM_RELEASE_KEY_ALIAS").orNull
        ?: System.getenv("MASTER_LLM_RELEASE_KEY_ALIAS")
    val releaseKeyPassword = providers.gradleProperty("MASTER_LLM_RELEASE_KEY_PASSWORD").orNull
        ?: System.getenv("MASTER_LLM_RELEASE_KEY_PASSWORD")

    val hasReleaseSigningConfig = !releaseStoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        create("release") {
            if (hasReleaseSigningConfig) {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            } else {
                // Keep release builds installable for local/dev workflows when no release keystore is configured.
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

android.applicationVariants.all {
    outputs.all {
        @Suppress("DEPRECATION")
        val apkOutput = this as com.android.build.gradle.api.ApkVariantOutput
        val normalizedVersion = versionName ?: "1.0.2"
        val normalizedBuildType = buildType.name
        apkOutput.outputFileName = "MasterLLM-v${normalizedVersion}-${normalizedBuildType}.apk"
    }
}

dependencies {
    implementation(project(":core-data"))
    implementation(project(":core-domain"))
    implementation(project(":core-network"))
    implementation(project(":core-ui"))

    implementation(project(":feature-auth"))
    implementation(project(":feature-marketplace"))
    implementation(project(":feature-model-manager"))
    implementation(project(":feature-chat"))
    implementation(project(":feature-image-gen"))
    implementation(project(":feature-roleplay"))
    implementation(project(":feature-settings"))

    implementation(project(":runtime-gguf"))
    implementation(project(":runtime-safetensors"))
    implementation(project(":runtime-imagegen"))

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.leakcanary.android)

    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.timber)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

val requiredTurnipAssetPaths = listOf(
    "src/main/assets/turnip/icd.d/freedreno_icd.aarch64.json",
    "src/main/assets/turnip/libvulkan_freedreno.so",
)

val verifyTurnipReleaseAssets by tasks.registering(VerifyTurnipAssetsTask::class) {
    group = "verification"
    description = "Ensures Turnip ICD and Vulkan vendor library are bundled for release builds."
    requiredAssetPaths.set(requiredTurnipAssetPaths)
    requiredAssetFiles.from(requiredTurnipAssetPaths.map { path -> File(project.projectDir, path) })
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }
    .configureEach {
        dependsOn(verifyTurnipReleaseAssets)
    }
