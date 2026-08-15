pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven("https://api.xposed.info/")
            }
            filter {
                includeGroup("de.robv.android.xposed")
            }
        }
    }
}

rootProject.name = "zalo-patch-module"
include(":app")
