package com.stronghold.android

import android.util.Log
import java.io.File
import kotlin.concurrent.thread

object NodeManager {

    private const val TAG = "StrongholdNode"

    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("node")
        System.loadLibrary("node_bridge")
    }

    external fun startNodeWithArguments(
        arguments: Array<String>
    ): Int

    /** node::Start 每个进程只能调用一次，所以 started 置位后永不复位。 */
    @Volatile
    private var started = false

    @Volatile
    private var failure: String? = null

    /** Node 已退出或启动失败时返回原因；运行中或尚未启动时返回 null。 */
    fun failureReason(): String? = failure

    fun startStronghold(root: File) {
        val entry = File(root, "server/index.js")

        check(entry.isFile) {
            "Stronghold server entry not found: ${entry.absolutePath}"
        }

        synchronized(this) {
            if (started) {
                Log.i(TAG, "Node already started")
                return
            }

            started = true
        }

        thread(name = "Stronghold-Node") {
            try {
                Log.i(TAG, "Starting Stronghold entry: ${entry.absolutePath}")

                val exitCode = startNodeWithArguments(arrayOf("node", entry.absolutePath))

                Log.i(TAG, "Stronghold Node exited with code $exitCode")
                failure = "Stronghold Node exited with code $exitCode; restart the app to start it again"
            } catch (t: Throwable) {
                Log.e(TAG, "Stronghold Node failed", t)
                failure = "Stronghold Node failed: ${t.message}; restart the app to start it again"
            }
        }
    }
}
