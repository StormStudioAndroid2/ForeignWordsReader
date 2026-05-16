@file:Suppress("DSL_SCOPE_VIOLATION")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    applyDefaultHierarchyTemplate()

    jvm()

    androidTarget {
        compilations.all {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
            }
        }
    }

    val iosTargets = listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    )

    iosTargets.forEach { target ->
        target.compilations.getByName("main") {
            cinterops {
                val udpipeAdapter by creating {
                    definitionFile.set(project.file("src/nativeInterop/cinterop/udpipeAdapter.def"))
                    includeDirs(rootProject.file("native/udpipe/adapter"))
                }
            }
        }

        target.binaries.all {
            linkerOpts(
                "-L${rootProject.file("native/udpipe/build/ios/${target.name}")}",
                "-lforeignwords_udpipe",
                "-lc++",
            )
        }
    }

    iosTargets
        .takeIf { "XCODE_VERSION_MAJOR" in System.getenv().keys } // Export the framework only for Xcode builds
        ?.forEach {
            // This `shared` framework is exported for app-ios-swift
            it.binaries.framework {
                baseName = "shared" // Used in app-ios-swift

                export(libs.decompose.decompose)
                export(libs.essenty.lifecycle)
            }
        }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.decompose.decompose)
                api(libs.essenty.lifecycle)
                implementation(libs.sqldelight.runtime)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.sqldelight.androidDriver)
                implementation(libs.androidx.sqlite.framework)
            }
        }
        val iosMain by getting {
            dependencies {
                implementation(libs.sqldelight.nativeDriver)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

sqldelight {
    databases {
        create("BookDatabase") {
            packageName.set("com.example.myapplication.shared.data")
        }
    }
}

android {
    namespace = "com.example.myapplication.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++11", "-fexceptions", "-frtti")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = rootProject.file("native/udpipe/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

val buildUdpipeIos by tasks.registering(Exec::class) {
    commandLine(rootProject.file("native/udpipe/build-ios.sh").absolutePath)
    inputs.files(
        fileTree(rootProject.file("native/udpipe/upstream/src")) {
            include("**/*.cpp", "**/*.h")
            exclude("rest_server/**", "udpipe.cpp")
        },
        fileTree(rootProject.file("native/udpipe/adapter")) {
            include("*.cpp", "*.h")
            exclude("udpipe_jni.cpp")
        },
    )
    outputs.dirs(
        rootProject.file("native/udpipe/build/ios/iosArm64"),
        rootProject.file("native/udpipe/build/ios/iosX64"),
        rootProject.file("native/udpipe/build/ios/iosSimulatorArm64"),
    )
}

tasks.configureEach {
    if (
        name.contains("CInteropUdpipeAdapter", ignoreCase = true) ||
        (name.contains("link", ignoreCase = true) && name.contains("Ios")) ||
        name.contains("assembleDebugAppleFrameworkForXcode")
    ) {
        dependsOn(buildUdpipeIos)
    }
}
