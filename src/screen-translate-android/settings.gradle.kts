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
    // The google() Maven repo only serves the Android build toolchain and AndroidX
    // (Apache-2.0 open source). No Google Play Services / ML Kit / Firebase artifacts are used.
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
  }
}

rootProject.name = "ScreenTranslate"
include(":app")
