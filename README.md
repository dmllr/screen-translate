# Screen Translate

**Translate anything on your Android screen, fully offline.**

Screen Translate puts a small bubble on top of your apps. Tap it, and the text on screen is
replaced by a translation, right where the original was. It works in games, manga readers, chat
apps, menus: anything you can see, including text inside images.

Everything runs on your phone. There are no accounts, no cloud APIs and no Google services.
After a one-time model download, it works in airplane mode.

> **Status:** early, but usable. There are no prebuilt releases yet; see
> [Building from source](#building-from-source).

## Features

- **Tap to translate.** Tap the bubble and the whole screen is translated. Tap again to dismiss.
- **Continuous mode (experimental).** Keeps the translation up to date as the screen changes,
  for example while you read a visual novel or scroll a chat.
- **Any language → your language.** The language of each text block is detected automatically,
  so a screen that mixes Japanese, English and Chinese is handled in one go. You can set a usual
  source language to use when detection is unsure.
- **Vertical text.** Vertical Japanese and Chinese (manga, novels) is read in the right order.
- **Blends in.** Translations are drawn on a background sampled from the app underneath, sized
  to fit the original block.
- **Private by design.** Screenshots never leave the device. The only network traffic is
  downloading the models you choose.

## Languages

**Translation** uses the models behind Firefox's built-in translation: about 60 languages
translate to English and back. A pair that doesn't involve English (for example
Japanese → German) goes through English automatically.

**Text recognition** covers these writing systems:

| Included in the base download | Optional add-ons |
|---|---|
| Japanese, Chinese (Simplified and Traditional), English, and other Latin-script languages (French, German, Spanish, Indonesian, …), Greek | Korean, Cyrillic (Russian, Ukrainian, Bulgarian, Serbian, …), Arabic script (Arabic, Persian, Urdu), Devanagari (Hindi, Marathi), Thai, Tamil, Telugu |

Hebrew, Bengali, Gujarati, Kannada and Malayalam can be translated, but there is no text
recognizer for them yet.

## Requirements

- Android 9 or newer
- A 64-bit ARM phone or tablet. x86_64 (emulators, Chromebooks) works too.
- About 90 MB of storage for Japanese → English. Each extra language direction adds 35–70 MB.

## Getting started

1. **Install** the app. For now, build it yourself; see [Building from source](#building-from-source).
2. **Pick your languages.** On the Home screen, choose *Translate into* and your usual source
   language.
3. **Download the models.** Tap **Download** under *Models needed*. This fetches the text reader,
   language detection and the translation models for your pair.
4. **Grant permissions.** *Draw over other apps* (for the bubble and the translations) and
   *Notifications* (for the running indicator with a Stop button).
5. **Start.** Tap **Start screen translation** and allow screen capture. The app steps aside and
   the bubble appears. Open any app and tap the bubble.

### The bubble

| Gesture | What it does |
|---|---|
| Tap | Translate the screen. Tap again, or tap the translation, to dismiss. |
| Tap (continuous mode) | Refresh the translation now. |
| Long-press | Switch between tap mode and continuous mode. |
| Drag | Move the bubble; it snaps to the nearest edge. |

To stop, use **Stop** in the notification or in the app.

### More languages

Open the **Models** tab. Download the translation directions you need (for example
*Korean → English*) and, for scripts outside the base set, the matching text reader add-on.
Models can be deleted from the same screen.

## Privacy

- Screen content is processed in memory on the device and is never stored or uploaded.
- The app connects to the internet only to download models, and only from their publishers:
  Mozilla (translation), PaddlePaddle on Hugging Face (text recognition) and Meta (language
  detection). Every file is checked against a known SHA-256 hash.
- No analytics, no ads, no accounts. ONNX Runtime's built-in telemetry is removed from the build.
- The app uses no Google Play Services, ML Kit or Google cloud APIs.

## Building from source

You need:

- Android Studio, or the Android SDK with NDK `29.0.14206865` and CMake `4.1.2`
- JDK 17+ (Android Studio's bundled JDK works)
- git
- Windows only: long paths enabled, because some dependency paths exceed 260 characters. In an
  admin PowerShell:
  `Set-ItemProperty HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem LongPathsEnabled 1`

```bash
git clone https://github.com/dmllr/screen-translate.git
cd screen-translate/src/screen-translate-android
./gradlew :app:installDebug
```

The first build downloads the native dependencies (pinned versions, into `_imported/`) and
compiles the translation engine for each CPU architecture. Expect 10–20 minutes; later builds
are quick.

Useful options:

```bash
# Build for one architecture only (much faster while developing)
./gradlew -Pscreentranslate.abis=arm64-v8a :app:installDebug   # phone
./gradlew -Pscreentranslate.abis=x86_64 :app:installDebug      # emulator

# Kotlin only, no native build: UI work and unit tests (the APK can't translate)
./gradlew -Pscreentranslate.skipNative=true :app:testDebugUnitTest

# Tests, lint and license-notice checks
./gradlew :app:check
```

Debug builds include a shell hook for testing models without the screen-capture flow:

```bash
adb shell "am broadcast -n com.eillance.screentranslate/.debug.DebugTranslateReceiver \
  --es from ja --es to en --es text '今日はいい天気ですね。'"
adb logcat -s DebugTranslate Marian
```

### How it works

```
bubble tap → capture the screen (MediaProjection)
           → find text (PaddleOCR detector) → read it (PaddleOCR recognizers)
           → group lines into paragraphs → detect each paragraph's language (fastText + script rules)
           → translate (Bergamot, via English when needed)
           → draw the translations over the original text
```

### Project layout

```
screen-translate/
├── src/
│   ├── cmake/                         native dependencies (import_remote) and their patches
│   └── screen-translate-android/      the Android app (Gradle project)
│       ├── app/src/main/java/…        Kotlin: UI, capture, overlay, OCR and translation pipeline
│       ├── app/src/main/cpp/          JNI bridge to the translation engine
│       └── gradle/licenses.gradle.kts open-source notice generation
└── _imported/                         downloaded dependency sources (created by the build, not committed)
```

## Known limitations

- Continuous mode is experimental. Fast animations or video keep the screen "changing", so the
  translation waits until things settle. Tap the bubble to force a refresh.
- Translation between two non-English languages goes through English, which costs some accuracy.
- Translations of vertical text are drawn horizontally inside the original column area.
- On Android 12+, translations in continuous mode are slightly see-through: Android requires
  that for overlays that let touches pass to the app underneath.

## Roadmap

- Tap a translated block to see the original or copy it
- Better layout for manga speech bubbles and vertical text
- Smoother continuous mode (track moving text instead of re-reading the screen)
- Wi-Fi-only model downloads
- Prebuilt releases, including an F-Droid build

## Acknowledgements

Screen Translate is built on excellent open-source work:

- [Bergamot](https://github.com/mozilla/translations) and the Firefox Translations models by
  Mozilla (MPL-2.0), running on [Marian NMT](https://marian-nmt.github.io/)
- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) models by PaddlePaddle (Apache-2.0)
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) by Microsoft (MIT)
- [fastText](https://fasttext.cc/) language identification by Meta (MIT code; CC BY-SA 3.0 model)

The full list of components and their license texts is in the app under
**Settings → About → Open-source licenses**. Models are downloaded from their publishers and are
not redistributed with the app.
