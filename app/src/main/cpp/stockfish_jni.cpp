// app/src/main/cpp/stockfish_jni.cpp
//
// JNI-обёртка для StockfishEngine.kt. Вся логика находится в StockfishBridge.
// Имена функций должны совпадать с external-методами класса
// com.example.chessmentor.data.engine.StockfishEngine.

#include <jni.h>

#include <android/log.h>

#include <string>

#include "stockfish_bridge.h"

namespace {

constexpr const char* kTag = "StockfishJni";

chessmentor::StockfishBridge& bridge() {
    static chessmentor::StockfishBridge instance;
    return instance;
}

std::string toStdString(JNIEnv* env, jstring value) {
    if (value == nullptr)
        return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr)
        return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring toJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_chessmentor_data_engine_StockfishEngine_nativeInit(JNIEnv* env, jobject /*thiz*/,
                                                                    jstring netPath) {
    const std::string path = toStdString(env, netPath);
    const bool        ok   = bridge().init(path);
    if (!ok) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Network file is missing or invalid: %s",
                            path.c_str());
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_chessmentor_data_engine_StockfishEngine_nativeDestroy(JNIEnv* /*env*/,
                                                                       jobject /*thiz*/) {
    bridge().destroy();
}

JNIEXPORT void JNICALL
Java_com_example_chessmentor_data_engine_StockfishEngine_nativeSetOption(JNIEnv* env,
                                                                         jobject /*thiz*/,
                                                                         jstring name,
                                                                         jstring value) {
    bridge().setOption(toStdString(env, name), toStdString(env, value));
}

JNIEXPORT jint JNICALL
Java_com_example_chessmentor_data_engine_StockfishEngine_nativeEvaluate(JNIEnv* env,
                                                                        jobject /*thiz*/,
                                                                        jstring fen,
                                                                        jint depth) {
    bool ok    = false;
    int  score = bridge().evaluate(toStdString(env, fen), depth, &ok);
    if (!ok) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "evaluate failed, returning 0");
        return 0;
    }
    return static_cast<jint>(score);
}

JNIEXPORT jstring JNICALL
Java_com_example_chessmentor_data_engine_StockfishEngine_nativeGetBestMove(JNIEnv* env,
                                                                           jobject /*thiz*/,
                                                                           jstring fen,
                                                                           jint depth) {
    const std::string move = bridge().bestMove(toStdString(env, fen), depth);
    if (move.empty())
        return nullptr;
    return toJString(env, move);
}

// Формат результата: "<bestMove>\t<score>\t<pv>", разбирается в decodeAnalysisLine.
JNIEXPORT jstring JNICALL
Java_com_example_chessmentor_data_engine_StockfishEngine_nativeGetBestMoveWithLine(
  JNIEnv* env, jobject /*thiz*/, jstring fen, jint depth) {
    const auto line = bridge().bestLine(toStdString(env, fen), depth);
    if (!line.ok)
        return nullptr;

    const std::string encoded = line.bestMove + "\t" + std::to_string(line.score) + "\t" + line.pv;
    return toJString(env, encoded);
}

}  // extern "C"
