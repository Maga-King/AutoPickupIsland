pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "ColorOSAutoPickupIsland"
include(":app")
include(":testapp")
include(":os4-api-stubs")
include(":collector-carrier")
include(":fixture-loader")
