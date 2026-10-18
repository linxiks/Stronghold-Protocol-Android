package com.stronghold.android

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object StrongholdInstaller {

    private const val TAG = "StrongholdInstaller"

    private const val ARCHIVE_NAME = "stronghold-runtime.zip"

    /** 运行时解包目录；[LocalArt] 要把本地美术装进去，所以路径只在这里定义一次。 */
    fun root(context: Context): File = File(context.filesDir, "stronghold")

    @Synchronized
    fun install(context: Context): File {
        val root = root(context)
        val previousRoot = File(context.filesDir, "stronghold-previous")
        // 上次更新若在目录切换中被系统终止，先恢复旧运行时与其中的插件。
        if (!root.exists() && previousRoot.exists()) {
            check(previousRoot.renameTo(root)) { "Unable to restore previous Stronghold runtime" }
        }
        val marker = File(root, ".installed-version")
        val stamp = installStamp(context)

        if (
            marker.isFile &&
            marker.readText().trim() == stamp &&
            validateRuntime(root)
        ) {
            if (previousRoot.exists() && !previousRoot.deleteRecursively()) {
                Log.w(TAG, "Unable to remove previous Stronghold runtime backup")
            }
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

        // 插件、工作台草稿及 config.json 由用户写入运行时；模板文档以本次 APK 的版本为准。
        File(root, "plugins").listFiles()
            ?.filterNot { it.name == "_template" || it.name == "README.md" || it.name == "AGENTS.md" }
            ?.forEach { plugin ->
                check(plugin.copyRecursively(File(tempRoot, "plugins/${plugin.name}"), overwrite = false)) {
                    "Unable to preserve plugin data: ${plugin.name}"
                }
            }

        File(tempRoot, ".installed-version")
            .writeText(stamp)

        if (previousRoot.exists()) {
            check(previousRoot.deleteRecursively()) { "Unable to clear previous Stronghold runtime backup" }
        }
        if (root.exists()) {
            check(root.renameTo(previousRoot)) { "Unable to back up previous Stronghold runtime" }
        }
        if (!tempRoot.renameTo(root)) {
            if (previousRoot.exists()) previousRoot.renameTo(root)
            error("Unable to activate Stronghold runtime")
        }
        if (previousRoot.exists() && !previousRoot.deleteRecursively()) {
            Log.w(TAG, "Unable to remove previous Stronghold runtime backup")
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

    // APK 每次安装或更新都会改变 lastUpdateTime，运行时随之重新解压，不用再手动维护版本号。
    @Suppress("DEPRECATION")
    private fun installStamp(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()

    private fun validateRuntime(root: File): Boolean {
        return File(root, "server/index.js").isFile &&
            File(root, "public/index.html").isFile &&
            File(root, "package.json").isFile &&
            File(root, "data").isDirectory &&
            File(root, "shared").isDirectory &&
            File(root, "plugins/_template").isDirectory &&
            File(root, "node_modules").isDirectory
    }
}
