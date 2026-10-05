package com.stronghold.android

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object StrongholdInstaller {

    private const val TAG = "StrongholdInstaller"

    private const val ARCHIVE_NAME = "stronghold-runtime.zip"
    private const val RUNTIME_VERSION = "0.1.1"

    fun install(context: Context): File {
        val root = File(context.filesDir, "stronghold")
        val marker = File(root, ".installed-version")

        if (
            marker.isFile &&
            marker.readText().trim() == RUNTIME_VERSION &&
            validateRuntime(root)
        ) {
            Log.i(TAG, "Stronghold runtime already installed")
            return root
        }

        Log.i(TAG, "Installing Stronghold runtime")

        val tempRoot =
            File(context.filesDir, "stronghold-installing")

        if (tempRoot.exists()) {
            tempRoot.deleteRecursively()
        }

        check(tempRoot.mkdirs()) {
            "Unable to create temporary runtime directory"
        }

        extractRuntime(context, tempRoot)

        check(validateRuntime(tempRoot)) {
            "Stronghold runtime validation failed"
        }

        File(tempRoot, ".installed-version")
            .writeText(RUNTIME_VERSION)

        if (root.exists()) {
            check(root.deleteRecursively()) {
                "Unable to remove previous Stronghold runtime"
            }
        }

        check(tempRoot.renameTo(root)) {
            "Unable to activate Stronghold runtime"
        }

        Log.i(
            TAG,
            "Stronghold runtime installed at ${root.absolutePath}"
        )

        return root
    }

    private fun extractRuntime(
        context: Context,
        destination: File
    ) {
        val canonicalRoot =
            destination.canonicalFile.path + File.separator

        context.assets.open(ARCHIVE_NAME).use { assetInput ->
            ZipInputStream(assetInput.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break

                    if (entry.name.isBlank()) {
                        zip.closeEntry()
                        continue
                    }

                    val output =
                        File(destination, entry.name)

                    val canonicalOutput =
                        output.canonicalFile.path

                    check(
                        canonicalOutput.startsWith(canonicalRoot)
                    ) {
                        "Invalid ZIP entry: ${entry.name}"
                    }

                    if (entry.isDirectory) {
                        if (!output.exists()) {
                            check(output.mkdirs()) {
                                "Unable to create ${output.absolutePath}"
                            }
                        }
                    } else {
                        output.parentFile?.let {
                            if (!it.exists()) {
                                check(it.mkdirs()) {
                                    "Unable to create ${it.absolutePath}"
                                }
                            }
                        }

                        FileOutputStream(output)
                            .buffered()
                            .use { outputStream ->
                                zip.copyTo(
                                    outputStream,
                                    DEFAULT_BUFFER_SIZE
                                )
                            }
                    }

                    zip.closeEntry()
                }
            }
        }
    }

    private fun validateRuntime(root: File): Boolean {
        return File(root, "server/index.js").isFile &&
            File(root, "public/index.html").isFile &&
            File(root, "package.json").isFile &&
            File(root, "data").isDirectory &&
            File(root, "shared").isDirectory &&
            File(root, "node_modules").isDirectory
    }
}