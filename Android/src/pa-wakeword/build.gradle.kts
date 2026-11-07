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
  implementation(pa.litert)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.ui)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  testImplementation(pa.junit)
  testImplementation(pa.coroutines.test)
  // Runs the ONNX copies of the feature models on the JVM (FeatureParityTest); not in the APK.
  testImplementation(pa.onnxruntime.jvm)
}

// The sherpa-onnx AAR and the models are not in git. Fail early with a clear message when they are
// missing (checked at execution time so other modules still configure and build without them).
val checkWakewordAssets by
  tasks.registering {
    val required =
      listOf(
        "localrepo/com/k2fsa/sherpa/onnx/sherpa-onnx-static-arm64/2.0.1/sherpa-onnx-static-arm64-2.0.1.aar",
        "src/main/assets/wakeword/melspectrogram.tflite",
        "src/main/assets/wakeword/embedding_model.tflite",
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

// FeatureParityTest runs the ONNX copies of the feature models from testmodels/ (also fetched by the
// script, never packaged).
val checkWakewordTestModels by
  tasks.registering {
    val files =
      listOf("testmodels/melspectrogram.onnx", "testmodels/embedding_model.onnx").map {
        layout.projectDirectory.file(it).asFile
      }
    doLast {
      if (files.any { !it.isFile }) {
        throw GradleException(
          "Wake-word test models missing. Run ./scripts/fetch-wakeword-assets.sh from the repo root."
        )
      }
    }
  }

tasks.withType<Test>().configureEach { dependsOn(checkWakewordTestModels) }
