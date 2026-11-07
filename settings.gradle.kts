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

rootProject.name = "Spektrafilm"
include(":app")
include(":engine:spektra-core")
include(":lib:libraw")
include(":lib:tiffwriter")
include(":lib:pngwriter")
// Baseline/Startup Profile generator and Macrobenchmarks for :app (#459). Test-only module.
include(":baselineprofile")
