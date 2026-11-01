// JNI bridge to fastText for language identification (lid.176.ftz).
//
// Kotlin side: com.eillance.screentranslate.langid.FastTextNative

#include <jni.h>
#include <android/log.h>

#include <memory>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "fasttext.h"

#define LOG_TAG "FastTextJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
void throwJava(JNIEnv* env, const char* message) {
  jclass cls = env->FindClass("java/lang/IllegalStateException");
  if (cls != nullptr) env->ThrowNew(cls, message);
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_eillance_screentranslate_langid_FastTextNative_nativeLoad(JNIEnv* env, jclass, jstring path) {
  const char* chars = env->GetStringUTFChars(path, nullptr);
  std::string p(chars);
  env->ReleaseStringUTFChars(path, chars);
  try {
    auto* model = new fasttext::FastText();
    model->loadModel(p);
    return reinterpret_cast<jlong>(model);
  } catch (const std::exception& e) {
    LOGE("load: %s", e.what());
    throwJava(env, e.what());
    return 0;
  }
}

JNIEXPORT void JNICALL
Java_com_eillance_screentranslate_langid_FastTextNative_nativeFree(JNIEnv*, jclass, jlong handle) {
  delete reinterpret_cast<fasttext::FastText*>(handle);
}

// Returns labels and probabilities interleaved: ["ja", "0.97", "zh", "0.02", ...].
// `text` must already be a single line (the Kotlin side replaces newlines).
JNIEXPORT jobjectArray JNICALL
Java_com_eillance_screentranslate_langid_FastTextNative_nativePredict(JNIEnv* env, jclass, jlong handle, jstring text,
                                                                     jint k) {
  auto* model = reinterpret_cast<fasttext::FastText*>(handle);
  jclass stringClass = env->FindClass("java/lang/String");
  if (model == nullptr) {
    throwJava(env, "model is null");
    return nullptr;
  }
  const char* chars = env->GetStringUTFChars(text, nullptr);
  std::string line(chars);
  env->ReleaseStringUTFChars(text, chars);
  line.push_back('\n');

  std::vector<std::pair<fasttext::real, std::string>> predictions;
  try {
    std::istringstream in(line);
    model->predictLine(in, predictions, k, 0.0f);
  } catch (const std::exception& e) {
    LOGE("predict: %s", e.what());
    throwJava(env, e.what());
    return nullptr;
  }

  static const std::string kPrefix = "__label__";
  jobjectArray out = env->NewObjectArray(static_cast<jsize>(predictions.size() * 2), stringClass, nullptr);
  for (size_t i = 0; i < predictions.size(); ++i) {
    std::string label = predictions[i].second;
    if (label.rfind(kPrefix, 0) == 0) label = label.substr(kPrefix.size());
    // Labels are ASCII ISO codes, so NewStringUTF is safe here.
    jstring l = env->NewStringUTF(label.c_str());
    jstring p = env->NewStringUTF(std::to_string(predictions[i].first).c_str());
    env->SetObjectArrayElement(out, static_cast<jsize>(i * 2), l);
    env->SetObjectArrayElement(out, static_cast<jsize>(i * 2 + 1), p);
    env->DeleteLocalRef(l);
    env->DeleteLocalRef(p);
  }
  return out;
}

}  // extern "C"
