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
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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
