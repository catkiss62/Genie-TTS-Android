#include <jni.h>
#include <sstream>
#include <string>

extern "C" {
typedef struct {
    char* phonemes;
    int* prosody_a1;
    int* prosody_a2;
    int* prosody_a3;
    int phoneme_count;
} OpenJTalkNativeProsodyResult;

void* openjtalk_native_create(const char* dict_path);
void openjtalk_native_destroy(void* handle);
OpenJTalkNativeProsodyResult* openjtalk_native_phonemize_with_prosody(void* handle, const char* text);
void openjtalk_native_free_prosody_result(OpenJTalkNativeProsodyResult* result);
int openjtalk_native_get_last_error(void* handle);
const char* openjtalk_native_get_error_string(int error_code);
}

namespace {
std::string csv(const int* values, int count) {
    std::ostringstream stream;
    for (int i = 0; i < count; ++i) {
        if (i) stream << ',';
        stream << values[i];
    }
    return stream.str();
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_catkiss62_geniettsbenchmark_NativeJapaneseFrontend_nativeCreate(
    JNIEnv* env, jobject, jstring dictionary_path) {
    const char* path = env->GetStringUTFChars(dictionary_path, nullptr);
    void* handle = openjtalk_native_create(path);
    env->ReleaseStringUTFChars(dictionary_path, path);
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_catkiss62_geniettsbenchmark_NativeJapaneseFrontend_nativePhonemize(
    JNIEnv* env, jobject, jlong raw_handle, jstring input) {
    auto* handle = reinterpret_cast<void*>(raw_handle);
    const char* text = env->GetStringUTFChars(input, nullptr);
    OpenJTalkNativeProsodyResult* result =
        openjtalk_native_phonemize_with_prosody(handle, text);
    env->ReleaseStringUTFChars(input, text);
    if (!result) return nullptr;

    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray output = env->NewObjectArray(4, string_class, nullptr);
    const std::string a1 = csv(result->prosody_a1, result->phoneme_count);
    const std::string a2 = csv(result->prosody_a2, result->phoneme_count);
    const std::string a3 = csv(result->prosody_a3, result->phoneme_count);
    env->SetObjectArrayElement(output, 0, env->NewStringUTF(result->phonemes));
    env->SetObjectArrayElement(output, 1, env->NewStringUTF(a1.c_str()));
    env->SetObjectArrayElement(output, 2, env->NewStringUTF(a2.c_str()));
    env->SetObjectArrayElement(output, 3, env->NewStringUTF(a3.c_str()));
    openjtalk_native_free_prosody_result(result);
    return output;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_catkiss62_geniettsbenchmark_NativeJapaneseFrontend_nativeLastError(
    JNIEnv* env, jobject, jlong raw_handle) {
    auto* handle = reinterpret_cast<void*>(raw_handle);
    const int error = openjtalk_native_get_last_error(handle);
    return env->NewStringUTF(openjtalk_native_get_error_string(error));
}

extern "C" JNIEXPORT void JNICALL
Java_com_catkiss62_geniettsbenchmark_NativeJapaneseFrontend_nativeDestroy(
    JNIEnv*, jobject, jlong raw_handle) {
    openjtalk_native_destroy(reinterpret_cast<void*>(raw_handle));
}
