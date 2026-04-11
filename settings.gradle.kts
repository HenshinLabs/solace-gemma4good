pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MasterLLM"

// :app
include(":app")

// Core modules
include(":core-data")
include(":core-domain")
include(":core-network")
include(":core-ui")

// Feature modules
include(":feature-auth")
include(":feature-marketplace")
include(":feature-model-manager")
include(":feature-chat")
include(":feature-image-gen")
include(":feature-roleplay")
include(":feature-settings")

// Runtime modules
include(":runtime-gguf")
include(":runtime-safetensors")
include(":runtime-imagegen")

// Testing modules
include(":testing:testing-shared")
include(":testing:testing-fixtures")
