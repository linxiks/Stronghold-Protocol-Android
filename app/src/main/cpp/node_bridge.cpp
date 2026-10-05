#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "node.h"

/*
 * Kotlin:
 *
 * package com.stronghold.android
 *
 * object NodeManager {
 *     external fun startNodeWithArguments(arguments: Array<String>): Int
 * }
 *
 * JNI name therefore becomes:
 * Java_com_stronghold_android_NodeManager_startNodeWithArguments
 */

extern "C"
JNIEXPORT jint JNICALL
Java_com_stronghold_android_NodeManager_startNodeWithArguments(
        JNIEnv* env,
        jobject /* this */,
        jobjectArray arguments) {

    if (arguments == nullptr) {
        return -1;
    }

    const jsize argumentCount = env->GetArrayLength(arguments);

    if (argumentCount <= 0) {
        return -1;
    }

    /*
     * Node/libuv expects argv strings to live in contiguous memory.
     *
     * First calculate how many bytes are required for:
     *
     *   arg0\0arg1\0arg2\0...
     */
    size_t bufferSize = 0;

    for (jsize i = 0; i < argumentCount; ++i) {
        auto argument =
                static_cast<jstring>(env->GetObjectArrayElement(arguments, i));

        if (argument == nullptr) {
            return -1;
        }

        const char* utf = env->GetStringUTFChars(argument, nullptr);

        if (utf == nullptr) {
            env->DeleteLocalRef(argument);
            return -1;
        }

        bufferSize += std::strlen(utf) + 1;

        env->ReleaseStringUTFChars(argument, utf);
        env->DeleteLocalRef(argument);
    }

    auto* argumentBuffer =
            static_cast<char*>(std::calloc(bufferSize, sizeof(char)));

    if (argumentBuffer == nullptr) {
        return -1;
    }

    std::vector<char*> argv(argumentCount);

    char* currentPosition = argumentBuffer;

    /*
     * Copy Java strings into the contiguous native buffer
     * and construct argv.
     */
    for (jsize i = 0; i < argumentCount; ++i) {
        auto argument =
                static_cast<jstring>(env->GetObjectArrayElement(arguments, i));

        if (argument == nullptr) {
            std::free(argumentBuffer);
            return -1;
        }

        const char* utf = env->GetStringUTFChars(argument, nullptr);

        if (utf == nullptr) {
            env->DeleteLocalRef(argument);
            std::free(argumentBuffer);
            return -1;
        }

        const size_t length = std::strlen(utf);

        std::memcpy(currentPosition, utf, length);
        currentPosition[length] = '\0';

        argv[i] = currentPosition;
        currentPosition += length + 1;

        env->ReleaseStringUTFChars(argument, utf);
        env->DeleteLocalRef(argument);
    }

    /*
     * Start the embedded Node.js runtime.
     *
     * This call normally blocks for as long as Node's event loop is alive,
     * therefore Kotlin MUST invoke this JNI method from a background thread.
     */
    const int result = node::Start(
            static_cast<int>(argumentCount),
            argv.data()
    );

    std::free(argumentBuffer);

    return static_cast<jint>(result);
}