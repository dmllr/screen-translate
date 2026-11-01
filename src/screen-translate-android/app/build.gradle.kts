import java.util.Properties

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

val repoRoot: File = rootDir.resolve("../..").canonicalFile

// ---------------------------------------------------------------------------
// Version: <repo>/VERSION holds MAJOR, MINOR, REVISION and BUILD.
// BUILD goes up by one on every local build (assemble/bundle/install/test/check/lint/build),
// manual or automated. CI (env CI=true, set by GitHub Actions) never modifies the file, and
// -Pscreentranslate.noVersionBump=true skips it locally. IDE sync runs no tasks, so no bump.
// ---------------------------------------------------------------------------
val versionFile = repoRoot.resolve("VERSION")
val versionKeys = listOf("MAJOR", "MINOR", "REVISION", "BUILD")

fun readVersion(): Map<String, Int> {
  if (!versionFile.isFile) throw GradleException("Missing ${versionFile.path}")
  val values = versionFile.readLines()
    .mapNotNull { Regex("""^\s*(\w+)\s*=\s*(\d+)\s*$""").find(it)?.destructured }
    .associate { (k, v) -> k to v.toInt() }
  val missing = versionKeys - values.keys
  if (missing.isNotEmpty()) throw GradleException("${versionFile.path} lacks $missing")
  return values
}

val isCi = providers.environmentVariable("CI").map { it.toBoolean() }.getOrElse(false)
val noVersionBump = providers.gradleProperty("screentranslate.noVersionBump").map { it.toBoolean() }.getOrElse(false)
val bumpingTask = Regex("(?i)(assemble|bundle|install|test|check|lint|build)")
val shouldBump = !isCi && !noVersionBump &&
  gradle.startParameter.taskNames.any { bumpingTask.containsMatchIn(it.substringAfterLast(':')) }

val appVersion: Map<String, Int> = readVersion().let { v ->
  if (!shouldBump) return@let v
  val build = v.getValue("BUILD") + 1
  versionFile.writeText(versionFile.readText().replace(Regex("""(?m)^(\s*BUILD\s*=\s*)\d+"""), "$1$build"))
  v + ("BUILD" to build)
}
val appVersionName = versionKeys.joinToString(".") { appVersion.getValue(it).toString() }
logger.lifecycle("Screen Translate version $appVersionName${if (shouldBump) " (BUILD bumped)" else ""}")

// ---------------------------------------------------------------------------
// Release signing: <repo>/keystore.config (git-ignored) with
//   storeFile=path/to/release.jks   (absolute, or relative to the repo root)
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
// If the config or the keystore it points to is missing, release builds are signed with the
// debug key instead (with a warning), so the build never fails because of signing.
// ---------------------------------------------------------------------------
val keystoreConfigFile = repoRoot.resolve("keystore.config")
val releaseKeystore: Properties? = keystoreConfigFile.takeIf { it.isFile }?.let { f ->
  val props = Properties().apply { f.inputStream().use { load(it) } }
  val required = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
  val missing = required.filter { props.getProperty(it).isNullOrBlank() }
  val store = props.getProperty("storeFile")?.let { repoRoot.resolve(it) }
  when {
    missing.isNotEmpty() -> { logger.warn("keystore.config lacks $missing; release uses debug signing"); null }
    store == null || !store.isFile -> { logger.warn("Keystore $store not found; release uses debug signing"); null }
    else -> props.apply { setProperty("storeFile", store.path) }
  }
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
    // BUILD only ever grows, so it is a valid monotonic versionCode.
    versionCode = appVersion.getValue("BUILD")
    versionName = appVersionName

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

  signingConfigs {
    if (releaseKeystore != null) create("release") {
      storeFile = file(releaseKeystore.getProperty("storeFile"))
      storePassword = releaseKeystore.getProperty("storePassword")
      keyAlias = releaseKeystore.getProperty("keyAlias")
      keyPassword = releaseKeystore.getProperty("keyPassword")
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Release key from keystore.config when available, otherwise the debug key.
      signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
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
