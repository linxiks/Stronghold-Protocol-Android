package com.stronghold.android

import android.util.Log
import java.io.File

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

    @Volatile
    private var started = false

    fun startStronghold(root: File) {
        synchronized(this) {
            if (started) {
                Log.i(TAG, "Node already started")
                return
            }

            started = true
        }

        Thread {
            try {
                val entry =
                    File(root, "server/index.js")

                check(entry.isFile) {
                    "Stronghold server entry not found: ${entry.absolutePath}"
                }

                Log.i(
                    TAG,
                    "Starting Stronghold entry: ${entry.absolutePath}"
                )

                val exitCode =
                    startNodeWithArguments(
                        arrayOf(
                            "node",
                            entry.absolutePath
                        )
                    )

                Log.i(
                    TAG,
                    "Stronghold Node exited with code $exitCode"
                )
            } catch (t: Throwable) {
                Log.e(
                    TAG,
                    "Stronghold Node failed",
                    t
                )
            }
        }.apply {
            name = "Stronghold-Node"
            start()
        }
    }
}