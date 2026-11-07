import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.library")
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "com.malladi2610.phoneagent.core"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }
  defaultConfig { minSdk = 31 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

dependencies {
  api(libs.kotlinx.serialization.json)
  api(pa.coroutines.core)
  api(pa.okhttp)
  testImplementation(pa.junit)
  testImplementation(pa.coroutines.test)
  testImplementation(pa.okhttp.mockwebserver)
}
