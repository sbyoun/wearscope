// WearScopeDAT (Android) — auto-instrumentation adapter for Meta Wearables DAT.
// mwdat artifacts are resolved via the host build's repository config (including GitHub Packages credentials).
plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "io.wearscope.dat"
  compileSdk = 36
  defaultConfig { minSdk = 31 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

dependencies {
  api(project(":wearscope"))
  implementation("com.meta.wearable:mwdat-core:0.8.0")
  implementation("com.meta.wearable:mwdat-camera:0.8.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
