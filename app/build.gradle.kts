plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val strongholdSource =
    rootProject.file("../Stronghold-Protocol")

val generatedStrongholdAssets =
    layout.buildDirectory.dir("generated/strongholdAssets")

val syncStronghold by tasks.registering(Sync::class) {
    group = "stronghold"
    description = "Packages Stronghold runtime files into Android assets"

    into(
        generatedStrongholdAssets.map {
            it.dir("stronghold")
        }
    )

    from(strongholdSource.resolve("data")) {
        into("data")
    }

    from(strongholdSource.resolve("public")) {
        into("public")
    }

    from(strongholdSource.resolve("server")) {
        into("server")
    }

    from(strongholdSource.resolve("shared")) {
        into("shared")
    }

    from(strongholdSource.resolve("node_modules")) {
        into("node_modules")

        exclude(
            ".cache/**",
            "**/.bin/**"
        )
    }

    from(strongholdSource) {
        include("package.json")
    }
}

val packageStronghold by tasks.registering(Zip::class) {
    group = "stronghold"
    description = "Packages Stronghold runtime into a single archive"

    dependsOn(syncStronghold)

    from(
        generatedStrongholdAssets.map {
            it.dir("stronghold")
        }
    )

    archiveFileName.set("stronghold-runtime.zip")

    destinationDirectory.set(
        layout.buildDirectory.dir("generated/strongholdPackage")
    )

    // Most Stronghold assets are already compressed media files.
    entryCompression = ZipEntryCompression.STORED
}

android {
    namespace = "com.stronghold.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.stronghold.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
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

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")

            assets.srcDir(
                layout.buildDirectory.dir(
                    "generated/strongholdPackage"
                )
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

tasks.named("preBuild") {
    dependsOn(packageStronghold)
}