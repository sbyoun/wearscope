// WearScope Android — standalone build (SDK modules + example app).
// Resolving mwdat artifacts requires a GitHub Packages token: github_token in local.properties or GITHUB_TOKEN.
import java.util.Properties

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

val localProperties = Properties().apply {
  val f = File(rootDir, "local.properties")
  if (f.exists()) f.inputStream().use { load(it) }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    maven {
      url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
      credentials {
        username = ""
        password = System.getenv("GITHUB_TOKEN") ?: localProperties.getProperty("github_token")
      }
    }
  }
}

rootProject.name = "wearscope-android"

include(":wearscope", ":wearscope-dat", ":examples:wearbench")
