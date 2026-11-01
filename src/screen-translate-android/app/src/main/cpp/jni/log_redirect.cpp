// Routes the native libraries' stdout/stderr to logcat.
//
// Android discards a process's stdout/stderr. Marian/Bergamot (via spdlog) report warnings and,
// crucially, the reason for an ABORT on stderr; without this redirect a model problem shows up only
// as an unexplained SIGABRT.

#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>

namespace {

int g_pipe[2] = {-1, -1};

void* pump(void*) {
  char buf[1024];
  char line[2048];
  size_t used = 0;
  ssize_t n;
  while ((n = read(g_pipe[0], buf, sizeof(buf))) > 0) {
    for (ssize_t i = 0; i < n; ++i) {
      char c = buf[i];
      if (c == '\n' || used == sizeof(line) - 1) {
        line[used] = '\0';
        if (used > 0) __android_log_write(ANDROID_LOG_WARN, "Marian", line);
        used = 0;
        if (c == '\n') continue;
      }
      line[used++] = c;
    }
  }
  return nullptr;
}

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
  if (pipe(g_pipe) == 0) {
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    dup2(g_pipe[1], STDOUT_FILENO);
    dup2(g_pipe[1], STDERR_FILENO);
    pthread_t thread;
    if (pthread_create(&thread, nullptr, pump, nullptr) == 0) {
      pthread_setname_np(thread, "native-log");
      pthread_detach(thread);
    }
  }
  return JNI_VERSION_1_6;
}
