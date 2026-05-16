#include <jni.h>

#include <string>

#include "udpipe_adapter.h"

namespace {

void throw_illegal_state(JNIEnv* env, const char* message) {
  jclass exception_class = env->FindClass("java/lang/IllegalStateException");
  if (exception_class != nullptr) {
    env->ThrowNew(exception_class, message == nullptr ? "UDPipe error." : message);
  }
}

std::string java_string_to_utf8(JNIEnv* env, jstring value) {
  if (value == nullptr) return std::string();
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (chars == nullptr) return std::string();
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_myapplication_shared_processing_AndroidUdpipeNative_createEngine(
    JNIEnv* env,
    jobject) {
  udpipe_adapter_engine* engine = udpipe_adapter_create_engine();
  if (engine == nullptr) {
    throw_illegal_state(env, "Could not create UDPipe engine.");
    return 0;
  }
  return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_shared_processing_AndroidUdpipeNative_loadModel(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring model_path) {
  std::string path = java_string_to_utf8(env, model_path);
  char* error = udpipe_adapter_load_model(
      reinterpret_cast<udpipe_adapter_engine*>(handle),
      path.c_str());
  if (error != nullptr) {
    throw_illegal_state(env, error);
    udpipe_adapter_free_string(error);
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_myapplication_shared_processing_AndroidUdpipeNative_analyzeUtf8(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring text) {
  std::string input = java_string_to_utf8(env, text);
  udpipe_adapter_result* result = udpipe_adapter_analyze_utf8(
      reinterpret_cast<udpipe_adapter_engine*>(handle),
      input.c_str());
  if (result == nullptr) {
    throw_illegal_state(env, "UDPipe processing failed.");
    return nullptr;
  }

  if (result->error != nullptr) {
    throw_illegal_state(env, result->error);
    udpipe_adapter_result_free(result);
    return nullptr;
  }

  jstring output = env->NewStringUTF(result->output == nullptr ? "" : result->output);
  udpipe_adapter_result_free(result);
  return output;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_myapplication_shared_processing_AndroidUdpipeNative_version(
    JNIEnv* env,
    jobject) {
  return env->NewStringUTF(udpipe_adapter_version());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_shared_processing_AndroidUdpipeNative_destroyEngine(
    JNIEnv*,
    jobject,
    jlong handle) {
  udpipe_adapter_destroy_engine(reinterpret_cast<udpipe_adapter_engine*>(handle));
}
