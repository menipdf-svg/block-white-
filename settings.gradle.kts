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
        // GeckoView — מנוע ה-Firefox
        maven("https://maven.mozilla.org/maven2/")
    }
}

rootProject.name = "WhitelistFilter"
include(":app")
