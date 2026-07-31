// WearBench (Android) — WearScope integration example + hardware diagnostics app (same concept as the iOS example).
// Setup: mwdat_application_id / mwdat_client_token / ws_api_key / ws_endpoint in local.properties.
import java.util.Properties

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
  val f = File(rootDir, "local.properties")
  if (f.exists()) f.inputStream().use { load(it) }
}

android {
  namespace = "io.wearscope.bench"
  compileSdk = 36

  defaultConfig {
    applicationId = "io.wearscope.bench"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "0.4"
    manifestPlaceholders["mwdat_application_id"] =
        localProperties.getProperty("mwdat_application_id", "")
    manifestPlaceholders["mwdat_client_token"] =
        localProperties.getProperty("mwdat_client_token", "")
    buildConfigField("String", "WS_API_KEY",
        "\"${localProperties.getProperty("ws_api_key", "")}\"")
    buildConfigField("String", "WS_ENDPOINT",
        "\"${localProperties.getProperty("ws_endpoint", "")}\"")
  }

  buildFeatures {
    buildConfig = true
    compose = true
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

dependencies {
  implementation(project(":wearscope"))
  implementation(project(":wearscope-dat"))
  implementation("com.meta.wearable:mwdat-core:0.8.0")
  implementation("com.meta.wearable:mwdat-camera:0.8.0")
  implementation(platform("androidx.compose:compose-bom:2026.05.01"))
  implementation("androidx.compose.material3:material3")
  implementation("androidx.activity:activity-compose:1.13.0")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
