import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.library")
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.malladi2610.phoneagent.wakeword"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }
  defaultConfig {
    minSdk = 31
    consumerProguardFiles("consumer-rules.pro")
  }
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
  implementation(pa.sherpa.onnx)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.ui)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  testImplementation(pa.junit)
  testImplementation(pa.coroutines.test)
}

// The sherpa-onnx AAR and the models are not in git. Fail early with a clear message when they are
// missing (checked at execution time so other modules still configure and build without them).
val checkWakewordAssets by
  tasks.registering {
    val required =
      listOf(
        "localrepo/com/k2fsa/sherpa/onnx/sherpa-onnx-static-arm64/2.0.1/sherpa-onnx-static-arm64-2.0.1.aar",
        "src/main/assets/wakeword/encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
        "src/main/assets/wakeword/decoder-epoch-13-avg-2-chunk-16-left-64.onnx",
        "src/main/assets/wakeword/joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
        "src/main/assets/wakeword/tokens.txt",
        "src/main/assets/wakeword/en.phone",
        "src/main/assets/wakeword/keywords.txt",
        "src/main/assets/wakeword/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
      )
    val files = required.map { layout.projectDirectory.file(it).asFile }
    doLast {
      val missing = files.filterNot { it.isFile }
      if (missing.isNotEmpty()) {
        throw GradleException(
          "Wake-word assets missing (${missing.joinToString { it.name }}). " +
            "Run ./scripts/fetch-wakeword-assets.sh from the repo root."
        )
      }
    }
  }

tasks.named("preBuild") { dependsOn(checkWakewordAssets) }
