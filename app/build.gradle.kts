import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val strongholdSource = rootProject.file("../Stronghold-Protocol")
val strongholdNodeModules = strongholdSource.resolve("node_modules")
val strongholdNodeLock = strongholdNodeModules.resolve(".package-lock.json")

val strongholdRequiredFiles = listOf(
    "data", "public", "server", "shared", "package.json", "node_modules/.package-lock.json"
).map(strongholdSource::resolve)

// npm 隐藏锁文件中标记 "dev": true 的包，路径相对 node_modules/（例如 "cliui/node_modules/string-width"）。
val strongholdDevPackages: Set<String> by lazy {
    if (!strongholdNodeLock.isFile) return@lazy emptySet()
    @Suppress("UNCHECKED_CAST")
    val packages = (JsonSlurper().parse(strongholdNodeLock) as Map<String, Any?>)["packages"]
        as? Map<String, Map<String, Any?>> ?: emptyMap()
    packages.filterValues { it["dev"] == true }.keys.map { it.removePrefix("node_modules/") }.toSet()
}

// release 签名：CI 通过 STRONGHOLD_KEYSTORE_* 注入；未提供时回退到 AGP 的 debug 签名，保证产物可安装。
val releaseKeystoreFile = System.getenv("STRONGHOLD_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.stronghold.android"
    compileSdk = 36
    // 与 jniLibs 中 libnode.so 配套的 libc++_shared.so 来自此版本 NDK（SHA256 已核对一致）。
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.stronghold.android"
        minSdk = 26
        targetSdk = 36
        versionCode = System.getenv("STRONGHOLD_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("STRONGHOLD_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                // libnode.so 依赖 libc++_shared.so，桥接库必须使用同一份 C++ 运行库。
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (releaseKeystoreFile != null) {
            create("release") {
                storeFile = file(releaseKeystoreFile)
                storePassword = System.getenv("STRONGHOLD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("STRONGHOLD_KEY_ALIAS")
                keyPassword = System.getenv("STRONGHOLD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
}

val strongholdRuntimeDir = layout.buildDirectory.dir("generated/strongholdRuntime")

val packageStrongholdRuntime by tasks.registering(Zip::class) {
    group = "stronghold"
    description = "Packages the Stronghold runtime into assets/stronghold-runtime.zip"
    archiveFileName.set("stronghold-runtime.zip")
    destinationDirectory.set(strongholdRuntimeDir)

    doFirst {
        strongholdRequiredFiles.filterNot { it.exists() }.forEach {
            throw GradleException(
                "Stronghold source missing: ${it.absolutePath}. " +
                    "Clone Stronghold-Protocol next to this project and run npm install."
            )
        }
    }

    from(strongholdSource) { include("package.json") }
    listOf("data", "public", "server", "shared").forEach { dir ->
        from(strongholdSource.resolve(dir)) {
            into(dir)
            // 游戏素材不随 APK 分发：由连接页下载或从用户的资源包读取。
            if (dir == "public") exclude("assets/**")
        }
    }
    from(strongholdNodeModules) {
        into("node_modules")
        exclude(".cache/**", "**/.bin/**", ".package-lock.json")
        exclude { element ->
            val path = element.relativePath.pathString
            strongholdDevPackages.any { path == it || path.startsWith("$it/") }
        }
    }
}

val strongholdFontsDir = layout.buildDirectory.dir("generated/strongholdFonts")
val strongholdFontFiles = listOf("novecento-wide-normal.otf", "bender-regular.otf")

val syncStrongholdFonts by tasks.registering(Sync::class) {
    group = "stronghold"
    description = "Copies upstream UI fonts into assets/fonts for the native connection page"
    val fontSource = strongholdSource.resolve("public/fonts")
    doFirst {
        strongholdFontFiles.map(fontSource::resolve).filterNot { it.isFile }.forEach {
            throw GradleException("Stronghold font missing: ${it.absolutePath}")
        }
    }
    from(fontSource) { include(strongholdFontFiles) }
    into(strongholdFontsDir.map { it.dir("fonts") })
}

val strongholdAssetIndexDir = layout.buildDirectory.dir("generated/strongholdAssetIndex")

val generateStrongholdAssetIndex by tasks.registering(Exec::class) {
    group = "stronghold"
    description = "Builds assets/asset-index.json (download URLs of every manifest asset)"
    val script = rootProject.file("tools/build-asset-index.mjs")
    val output = strongholdAssetIndexDir.map { it.file("asset-index.json") }
    inputs.file(script)
    inputs.files(
        listOf(
            "data/assets.json", "data/enemies.json", "data/tokens.json", "data/bosses.json",
            "docs/research/03-operators.json", "docs/research/05-enemies.json",
            "docs/research/05-maps.json", "docs/research/07-assets.json",
            ".cache/assets-ledger.json", ".cache/gamedata/excel/audio_data.json",
            ".cache/ark-models/models_data.json",
        ).map(strongholdSource::resolve)
    )
    inputs.files(fileTree(strongholdSource.resolve("tools/assets")))
    outputs.file(output)
    commandLine("node", script.absolutePath, strongholdSource.absolutePath, output.get().asFile.absolutePath)
}

android.sourceSets.getByName("main").assets.srcDirs(strongholdRuntimeDir, strongholdFontsDir, strongholdAssetIndexDir)

tasks.named("preBuild") {
    dependsOn(packageStrongholdRuntime, syncStrongholdFonts, generateStrongholdAssetIndex)
}
