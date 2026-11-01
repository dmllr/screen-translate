# Screen Translate

On-device screen translation for Android. A floating bubble captures the screen, reads the text
with OCR, detects the language of each text block, and paints a translation over the original.
Japanese → English is the primary use case. The design works for **any language → any language**:
the source language is detected per block, and the user-chosen source language is the fallback.

Package: `com.eillance.screentranslate` · minSdk 28 · arm64-v8a and x86_64 (emulators, Chromebooks)

```
screen-translate/
├── README.md
├── _imported/                           third-party sources cloned by CMake (git-ignored)
└── src/
    ├── cmake/                           shared CMake: setup, import_remote, lib*.cmake, patches/
    └── screen-translate-android/        Gradle project
        ├── app/src/main/java/…           Kotlin (UI, capture, overlay, OCR pipeline)
        └── app/src/main/cpp/             JNI bridge + CMakeLists.txt (uses src/cmake)
```

## No-Google stack

"No Google" here means: no Google Play Services, no ML Kit, no Firebase, no Google Cloud APIs,
and no network calls to Google at runtime. Every model runs on the device. The network is used
only to download models, and only from the hosts listed below.

| Stage | Component | License | Notes |
|---|---|---|---|
| Screen capture | Android `MediaProjection` | (platform) | Full-display capture into an `ImageReader` |
| Text detection | **PaddleOCR PP-OCRv6** DB detector (small / tiny) | Apache-2.0 | Official PaddlePaddle ONNX exports |
| Text recognition | **PP-OCRv6 small** recognizer (CJK + Latin + Greek in one model) | Apache-2.0 | 88% on PaddleOCR's Japanese benchmark (v5 mobile: 76%) |
| Extra scripts | PP-OCRv5 mobile recognizers: Korean, Cyrillic, Arabic, Devanagari, Thai, Greek, Tamil, Telugu | Apache-2.0 | Optional add-ons. They are tried only on lines where the core reader is unsure |
| OCR runtime | **ONNX Runtime** (`com.microsoft.onnxruntime:onnxruntime-android`) | MIT | From Maven Central |
| Language ID | **fastText lid.176.ftz** (Meta) + Unicode script heuristics | MIT / CC BY-SA 3.0 | Kana ⇒ Japanese and Hangul ⇒ Korean without the classifier. Kanji-only labels take the screen's language |
| Translation | **Bergamot / Firefox Translations** (`mozilla/translations` inference engine, Marian) via NDK | MPL-2.0 | Same engine and models Firefox uses |
| Translation models | Mozilla Remote Settings `translations-models` | MPL-2.0 | Firefox's Android-specific builds are used where they exist |

**Model hosts:** `firefox.settings.services.mozilla.com` and `firefox-settings-attachments.cdn.mozilla.net`
(Mozilla), `huggingface.co` (PaddlePaddle's official repos, pinned to exact commits),
`dl.fbaipublicfiles.com` (Meta). Every file is checked against a pinned or registry-provided SHA-256.

**Things to know:**
- Bergamot's float matrix-multiply backend is **ruy** on both ABIs (on x86_64, int8 math uses intgemm),
  an Apache-2.0 library authored by Google.
  It is compiled from source into the app and contains no services or telemetry, but it is
  Google-authored code. If you want zero Google-authored source, flag it. The alternative is
  CTranslate2 with OpenBLAS and OPUS-MT models, which are noticeably weaker for ja→en.
- ONNX Runtime and AndroidX are also built on Google-authored open-source libraries (protobuf,
  XNNPACK, and AndroidX itself). Avoiding those is not realistic on Android.
- Mozilla's attachment CDN is backed by Google Cloud Storage (it returns `x-goog-*` headers),
  but the domain and service are Mozilla's.

### Why these choices

- **Bergamot over NLLB / M2M-100.** Firefox's distilled models are about 20–45 MB per direction,
  run at interactive speed on a phone CPU with int8 GEMM, and handle ja→en well. NLLB-600M is
  10–20× larger and slower. The trade-off: Bergamot models only go to and from English, so
  X → Y pivots through English. That doubles latency and compounds errors for non-English pairs.
  As of 2026-11, the catalog has 59 languages → en and 57 en → languages.
- **PP-OCRv6 over Tesseract.** Tesseract is weak on Japanese UI text and has no vertical-text
  handling. PaddleOCR recognizes vertical columns once they are rotated 90°, which the pipeline does.
- **ONNX Runtime for OCR, NDK for MT.** ORT ships as a Maven AAR, so the OCR side needs no
  custom native code. Bergamot has no Android artifact, so it is built from source.

## How it works

```
bubble tap ─▶ hide overlay ─▶ grab clean frame (VirtualDisplay → ImageReader, RGBA)
          ─▶ DB detector (ORT) ─▶ boxes ─▶ CTC recognizer(s) (ORT) ─▶ lines
          ─▶ LayoutAnalyzer: lines → blocks (paragraphs, vertical columns read right-to-left)
          ─▶ LanguageDetector: script rules → fastText → screen-majority vote → fallback language
          ─▶ per source language: route (direct, or pivot via English) → Bergamot (JNI, AsyncService)
          ─▶ overlay: each block painted on a background sampled around it, with auto-fitted text
```

### Modes

- **Tap to translate (phase 1).** Tap the bubble to translate. Tap the translation, or the bubble,
  to dismiss. While translations are showing, the overlay layer is opaque and touchable.
- **Continuous (phase 2, experimental).** Long-press the bubble to switch modes. After each
  translation the app captures the screen *with the translations showing* and stores a
  luminance-grid signature of it (`ChangeDetector`). Each check compares the live screen with that
  reference; an unchanged screen matches exactly, overlay included. The bubble and the status bar
  are masked out. When the content has changed **and then settled** (two matching samples, which
  debounces scrolling), the app grabs a clean frame (the overlay is hidden for that one frame
  only), translates, and swaps in the new overlay. Unchanged blocks come from a Kotlin-side cache
  and Bergamot's own sentence cache. In this mode the overlay passes touches through, which
  Android 12+ allows only at ≤ 80% window opacity. Content changing *underneath* an opaque
  translation box is only partly visible to the detector; tap the bubble to force a refresh.

## Building

Requirements: Android SDK with NDK `29.0.14206865` and CMake `4.1.2`, JDK 17+ (Android Studio's
JBR works), and git. On Windows, enable long paths
(`Set-ItemProperty HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem LongPathsEnabled 1`):
the imported trees produce object paths longer than 260 characters.

```bash
cd src/screen-translate-android
./gradlew :app:assembleDebug         # first build compiles Marian once per ABI: expect 10–20 min each
```

Native dependencies are pulled by `src/cmake/import_remote.cmake` on the first CMake configure.
Each one is pinned and cloned once into `_imported/`, which all ABIs share:

| Module | Source | Pin |
|---|---|---|
| `libbergamot.cmake` | `mozilla/translations` (`inference/`), only the 5 submodules inference needs | `8eada038fd45` |
| `libpcre2.cmake` | `PCRE2Project/pcre2` | `pcre2-10.39` |
| `libfasttext.cmake` | `facebookresearch/fastText` | `v0.9.2` |

`src/cmake/patches/translations.patch` carries two fixes for NDK 29: a constexpr enum cast in
SentencePiece, and a missing `<x86intrin.h>` in faiss on bionic. To bump a dependency, change its
`TAG` and delete the matching `_imported/…` folder.

Both ABIs are built by default. To build just one while iterating:

```bash
./gradlew -Pscreentranslate.abis=x86_64 :app:installDebug      # emulator
./gradlew -Pscreentranslate.abis=arm64-v8a :app:installDebug   # phone
```

Kotlin-only iteration (UI or pipeline work, unit tests) without the NDK build:

```bash
./gradlew -Pscreentranslate.skipNative=true :app:testDebugUnitTest :app:assembleDebug
```

That APK runs, but translation fails because `libscreentranslate.so` is missing.

Debug builds include a test hook that skips screen capture, for checking models and the pipeline
from a shell (`adb logcat -s DebugTranslate Marian`):

```bash
# Translate a string with the installed models (repeat/workers stress the worker threads)
adb shell "am broadcast -n com.eillance.screentranslate/.debug.DebugTranslateReceiver   --es from ja --es to ru --es text '今日はいい天気ですね。' --ei repeat 50 --ei workers 2"
# Full pipeline (OCR → language ID → translation) on a screenshot
adb shell screencap -p /data/local/tmp/s.png
adb shell "am broadcast -n com.eillance.screentranslate/.debug.DebugTranslateReceiver --es image /data/local/tmp/s.png"
```

Native stdout/stderr (Marian's error and abort messages) is forwarded to logcat under the tag `Marian`.
Native code is always compiled `-O3` (Marian at `-O0` is unusably slow), even in debug builds.

## Licenses

Every component permits commercial use, and nothing obliges the app's own code to be open-sourced
(no GPL/LGPL/AGPL code is compiled in). The app must ship the notices, which it does under
**Settings → About → Open-source licenses**:

- MIT/BSD/Apache/zlib texts for everything compiled in, plus ONNX Runtime's `ThirdPartyNotices.txt`.
- An MPL-2.0 source notice for Bergamot and Eigen (both unmodified, so a link to the exact upstream
  revision is enough).
- Credits for the downloadable models: Mozilla (MPL-2.0), PaddlePaddle (Apache-2.0), and fastText
  lid.176 (CC BY-SA 3.0). The models are fetched from their publishers and not redistributed. If you
  mirror them, keep their licenses alongside, and keep the fastText model CC BY-SA.

The component table lives in `src/screen-translate-android/gradle/licenses.gradle.kts`.
`./gradlew :app:updateLicenses` regenerates `app/src/main/assets/licenses/` from the pinned sources
in `_imported/` and from ONNX Runtime's release for the catalog version. `verifyLicenses` runs as
part of `check` and fails when a dependency bump leaves the committed notices stale. Do not add
ssplit-cpp's LGPL `nonbreaking_prefixes` files without revisiting this.

## Using it

1. Open the app and pick **Translate into** (default English) and a **usual source language** (default Japanese).
2. Tap **Download** in *Models needed*. That fetches the detector, the core reader, language ID
   and the translation directions you need, about 90 MB for ja→en.
3. Grant *Draw over other apps* and *Notifications*.
4. Tap **Start screen translation**, accept the capture prompt, then use the bubble in any app.
5. More languages: **Models** tab. For example, Korean on screen with English as the target needs
   the Korean text reader and `ko → en`.

## Status and roadmap

**Done (phase 1 scaffold, plus the core of phase 2):**
- Full pipeline and UI.
- Model manager with integrity checks.
- Mozilla catalog parsing, including its JEXL filter expressions.
- Multi-recognizer OCR.
- Per-block language detection with screen context.
- English pivoting.
- Both modes.

27 JVM unit tests cover the catalog, OCR post-processing, CTC decoding, layout, language detection,
routing and change detection.

**Not yet verified:**
- The native (Bergamot) build has not been run yet.
- Nothing has run on a device yet.

Expect to tune OCR thresholds, Marian compile flags, and overlay layout once it runs on real screens.

**Next:**
- Per-block "tap to see original" and copy.
- Better text placement for vertical text (manga): render vertically or fit to the speech bubble.
- Inpainting instead of solid backgrounds.
- Text tracking in continuous mode, to update moved blocks without a full OCR pass.
- An NNAPI/GPU execution provider for OCR, if it can stay free of Google dependencies.
- An optional direct-pair engine (e.g. NLLB via CTranslate2) for non-English pairs where pivoting hurts.
- Downloading models only over Wi-Fi.
- An F-Droid build flavor.
