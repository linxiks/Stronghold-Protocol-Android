#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "node.h"

namespace {

constexpr const char* kLogTag = "StrongholdNode";
// logcat 单条消息上限约 4 KB，超长行分段写入。
constexpr size_t kMaxLogLine = 4000;

void pumpToLogcat(int readFd) {
    char buffer[1024];
    std::string line;
    ssize_t count;
    while ((count = read(readFd, buffer, sizeof(buffer))) > 0) {
        for (ssize_t i = 0; i < count; ++i) {
            if (buffer[i] == '\n' || line.size() >= kMaxLogLine) {
                __android_log_write(ANDROID_LOG_INFO, kLogTag, line.c_str());
                line.clear();
                if (buffer[i] == '\n') continue;
            }
            line.push_back(buffer[i]);
        }
    }
    if (!line.empty()) {
        __android_log_write(ANDROID_LOG_INFO, kLogTag, line.c_str());
    }
    close(readFd);
}

// Android 进程的 fd 1/2 默认指向 /dev/null；Node（libuv）直接写 fd，所以在 fd 层重定向。
void redirectStdioToLogcat() {
    static std::once_flag once;
    std::call_once(once, [] {
        int fds[2];
        if (pipe(fds) != 0) {
            __android_log_write(ANDROID_LOG_WARN, kLogTag, "pipe() failed; Node stdout/stderr not captured");
            return;
        }
        std::setvbuf(stdout, nullptr, _IOLBF, 0);
        std::setvbuf(stderr, nullptr, _IONBF, 0);
        dup2(fds[1], STDOUT_FILENO);
        dup2(fds[1], STDERR_FILENO);
        close(fds[1]);
        std::thread(pumpToLogcat, fds[0]).detach();
    });
}

}  // namespace

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
    redirectStdioToLogcat();

    const int result = node::Start(
            static_cast<int>(argumentCount),
            argv.data()
    );

    std::free(argumentBuffer);

    return static_cast<jint>(result);
}