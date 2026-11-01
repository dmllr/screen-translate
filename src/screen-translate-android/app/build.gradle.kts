plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

// -Pscreentranslate.skipNative=true builds/tests the Kotlin side without the NDK build
// (the resulting APK cannot translate: libscreentranslate.so is missing).
val skipNative = providers.gradleProperty("screentranslate.skipNative").map { it.toBoolean() }.getOrElse(false)

// ABIs to build. Each ABI compiles Marian separately, so while iterating build just one, e.g.
// -Pscreentranslate.abis=x86_64 for the emulator or -Pscreentranslate.abis=arm64-v8a for a phone.
val supportedAbis = listOf("arm64-v8a", "x86_64")
val abis: List<String> = providers.gradleProperty("screentranslate.abis")
  .map { p -> p.split(',').map(String::trim).filter(String::isNotEmpty) }
  .getOrElse(supportedAbis)
  .also { requested ->
    val unsupported = requested - supportedAbis.toSet()
    require(unsupported.isEmpty()) { "Unsupported ABI(s) $unsupported; supported: $supportedAbis" }
  }

android {
  namespace = "com.eillance.screentranslate"
  compileSdk = 37
  ndkVersion = "29.0.14206865"

  defaultConfig {
    applicationId = "com.eillance.screentranslate"
    // 28: Marian (via pathie-cpp) needs glob() and iconv(), which bionic exposes from API 28.
    minSdk = 28
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"

    ndk {
      abiFilters += abis
    }
    if (!skipNative) externalNativeBuild {
      cmake {
        arguments += listOf(
          "-DANDROID_STL=c++_shared",
          "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
        )
        // Only our JNI library (and what it links). Otherwise AGP builds every executable in the
        // imported trees, e.g. SentencePiece's command-line tools.
        targets += "screentranslate"
      }
    }
  }

  if (!skipNative) externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "4.1.2"   // 4.x bundles Ninja 1.12 (Windows long paths)
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Sign with the debug key until a release keystore is configured.
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  packaging {
    jniLibs {
      // Keep .so files uncompressed and page-aligned in the APK.
      useLegacyPackaging = false
    }
  }

  testOptions {
    unitTests.isReturnDefaultValues = true
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.service)
  implementation(libs.kotlinx.coroutines.android)

  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.material3)
  implementation(libs.compose.material.icons.extended)
  debugImplementation(libs.compose.ui.tooling)

  // OCR inference (PaddleOCR models exported to ONNX). MIT licensed, Maven Central.
  implementation(libs.onnxruntime.android)

  testImplementation(libs.junit)
  // Android's org.json is a stub in local unit tests; use the real implementation there.
  testImplementation(libs.json)
}

// Open-source license notices (assets/licenses), see gradle/licenses.gradle.kts.
apply(from = rootProject.file("gradle/licenses.gradle.kts"))
