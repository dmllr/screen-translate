// JNI bridge to Mozilla's Bergamot translator (the engine behind Firefox Translations).
//
// Kotlin side: com.eillance.screentranslate.translate.BergamotNative
//
// Handles are raw pointers boxed into jlong:
//   service -> marian::bergamot::AsyncService*
//   model   -> std::shared_ptr<marian::bergamot::TranslationModel>*  (heap-allocated shared_ptr)

#include <jni.h>
#include <android/log.h>

#include <future>
#include <memory>
#include <string>
#include <vector>

#include "translator/parser.h"
#include "translator/response.h"
#include "translator/response_options.h"
#include "translator/service.h"

#define LOG_TAG "BergamotJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using marian::bergamot::AsyncService;
using marian::bergamot::Response;
using marian::bergamot::ResponseOptions;
using marian::bergamot::TranslationModel;
using ModelPtr = std::shared_ptr<TranslationModel>;

namespace {

void throwJava(JNIEnv* env, const char* message) {
  jclass cls = env->FindClass("java/lang/IllegalStateException");
  if (cls != nullptr) env->ThrowNew(cls, message);
}

std::string toStdString(JNIEnv* env, jstring s) {
  if (s == nullptr) return {};
  const char* chars = env->GetStringUTFChars(s, nullptr);
  std::string out(chars);
  env->ReleaseStringUTFChars(s, chars);
  return out;
}

// JNI's NewStringUTF expects *modified* UTF-8 and rejects 4-byte sequences (emoji etc).
// Build the java.lang.String from UTF-16 instead.
jstring toJString(JNIEnv* env, const std::string& utf8) {
  std::u16string u16;
  u16.reserve(utf8.size());
  size_t i = 0;
  while (i < utf8.size()) {
    unsigned char c = static_cast<unsigned char>(utf8[i]);
    uint32_t cp;
    int len;
    if (c < 0x80) { cp = c; len = 1; }
    else if ((c >> 5) == 0x6) { cp = c & 0x1F; len = 2; }
    else if ((c >> 4) == 0xE) { cp = c & 0x0F; len = 3; }
    else if ((c >> 3) == 0x1E) { cp = c & 0x07; len = 4; }
    else { ++i; u16.push_back(0xFFFD); continue; }
    if (i + len > utf8.size()) { u16.push_back(0xFFFD); break; }
    for (int k = 1; k < len; ++k) cp = (cp << 6) | (static_cast<unsigned char>(utf8[i + k]) & 0x3F);
    i += len;
    if (cp >= 0x10000) {
      cp -= 0x10000;
      u16.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
      u16.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
    } else {
      u16.push_back(static_cast<char16_t>(cp));
    }
  }
  return env->NewString(reinterpret_cast<const jchar*>(u16.data()), static_cast<jsize>(u16.size()));
}

std::vector<std::string> toStringVector(JNIEnv* env, jobjectArray array) {
  std::vector<std::string> out;
  jsize n = env->GetArrayLength(array);
  out.reserve(n);
  for (jsize i = 0; i < n; ++i) {
    auto s = static_cast<jstring>(env->GetObjectArrayElement(array, i));
    out.push_back(toStdString(env, s));
    env->DeleteLocalRef(s);
  }
  return out;
}

jobjectArray toJStringArray(JNIEnv* env, const std::vector<std::string>& values) {
  jclass stringClass = env->FindClass("java/lang/String");
  jobjectArray out = env->NewObjectArray(static_cast<jsize>(values.size()), stringClass, nullptr);
  for (size_t i = 0; i < values.size(); ++i) {
    jstring s = toJString(env, values[i]);
    env->SetObjectArrayElement(out, static_cast<jsize>(i), s);
    env->DeleteLocalRef(s);
  }
  return out;
}

// Translates every text through `first` (and then `second`, if non-null, i.e. pivoting).
// AsyncService spreads the sentences over its worker threads; we just wait for all futures.
std::vector<std::string> runTranslation(AsyncService* service, const ModelPtr& first, const ModelPtr* second,
                                        std::vector<std::string>&& texts, bool html) {
  std::vector<std::future<Response>> futures;
  futures.reserve(texts.size());
  ResponseOptions options;
  options.HTML = html;
  for (auto& text : texts) {
    auto promise = std::make_shared<std::promise<Response>>();
    futures.push_back(promise->get_future());
    auto callback = [promise](Response&& response) { promise->set_value(std::move(response)); };
    if (second != nullptr) {
      service->pivot(first, *second, std::move(text), callback, options);
    } else {
      service->translate(first, std::move(text), callback, options);
    }
  }
  std::vector<std::string> out;
  out.reserve(futures.size());
  for (auto& f : futures) out.push_back(f.get().target.text);
  return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_eillance_screentranslate_translate_BergamotNative_nativeCreateService(JNIEnv* env, jclass, jint workers,
                                                                              jint cacheSize) {
  try {
    AsyncService::Config config;
    config.numWorkers = workers > 0 ? static_cast<size_t>(workers) : 1;
    config.cacheSize = cacheSize > 0 ? static_cast<size_t>(cacheSize) : 0;
    // "error": ABORT reasons (logged as critical) reach logcat via log_redirect.cpp, without the
    // per-model "missing ssplit prefix file" warning.
    config.logger.level = "error";
    return reinterpret_cast<jlong>(new AsyncService(config));
  } catch (const std::exception& e) {
    LOGE("createService: %s", e.what());
    throwJava(env, e.what());
    return 0;
  }
}

JNIEXPORT void JNICALL
Java_com_eillance_screentranslate_translate_BergamotNative_nativeDestroyService(JNIEnv*, jclass, jlong handle) {
  auto* service = reinterpret_cast<AsyncService*>(handle);
  if (service != nullptr) {
    service->clear();
    delete service;
  }
}

JNIEXPORT jlong JNICALL
Java_com_eillance_screentranslate_translate_BergamotNative_nativeLoadModel(JNIEnv* env, jclass, jlong serviceHandle,
                                                                          jstring yamlConfig) {
  auto* service = reinterpret_cast<AsyncService*>(serviceHandle);
  if (service == nullptr) {
    throwJava(env, "service is null");
    return 0;
  }
  try {
    auto options = marian::bergamot::parseOptionsFromString(toStdString(env, yamlConfig), /*validate=*/false);
    auto* holder = new ModelPtr(service->createCompatibleModel(options));
    return reinterpret_cast<jlong>(holder);
  } catch (const std::exception& e) {
    LOGE("loadModel: %s", e.what());
    throwJava(env, e.what());
    return 0;
  }
}

JNIEXPORT void JNICALL
Java_com_eillance_screentranslate_translate_BergamotNative_nativeFreeModel(JNIEnv*, jclass, jlong handle) {
  delete reinterpret_cast<ModelPtr*>(handle);
}

JNIEXPORT jobjectArray JNICALL
Java_com_eillance_screentranslate_translate_BergamotNative_nativeTranslate(JNIEnv* env, jclass, jlong serviceHandle,
                                                                          jlong modelHandle, jlong pivotModelHandle,
                                                                          jobjectArray texts, jboolean html) {
  auto* service = reinterpret_cast<AsyncService*>(serviceHandle);
  auto* first = reinterpret_cast<ModelPtr*>(modelHandle);
  auto* second = reinterpret_cast<ModelPtr*>(pivotModelHandle);
  if (service == nullptr || first == nullptr) {
    throwJava(env, "service or model is null");
    return nullptr;
  }
  try {
    auto result = runTranslation(service, *first, second, toStringVector(env, texts), html == JNI_TRUE);
    return toJStringArray(env, result);
  } catch (const std::exception& e) {
    LOGE("translate: %s", e.what());
    throwJava(env, e.what());
    return nullptr;
  }
}

}  // extern "C"
