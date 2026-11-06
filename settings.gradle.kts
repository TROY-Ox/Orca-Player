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

rootProject.name = "ORCA-Player"

include(":app")
include(":core:design")
include(":core:model")
include(":core:settings")
include(":core:data")
include(":sources:api")
include(":sources:local")
include(":sources:stremio")
include(":player:engine")
include(":player:subtitles")
include(":player:audio")
include(":tools")
include(":downloads")
include(":sources:cloudstream")
