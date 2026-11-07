import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.library")
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.malladi2610.phoneagent.voice"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }
  defaultConfig { minSdk = 31 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  testOptions { unitTests.isReturnDefaultValues = true }
  buildFeatures { compose = true }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

dependencies {
  api(project(":pa-core"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.ui)
  testImplementation(pa.junit)
  testImplementation(pa.coroutines.test)
}
