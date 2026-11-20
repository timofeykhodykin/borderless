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

rootProject.name = "borderless"

// Kotlin/Native keeps its compiler and dependencies inside the project (like Go in .gradle/go), not in ~/.konan; it
// wants an absolute path, so it is set here from the project's own directory.
gradle.beforeProject { extensions.extraProperties["konan.data.dir"] = rootDir.resolve(".gradle/konan").absolutePath }
include(":android")
include(":shared")
