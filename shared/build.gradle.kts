// The code Android and macOS share (local/macos/PLAN.md §1.3, K1): the engine, the data, the parsers. Android uses the
// JVM target; the Mac service and app use a static library for the host's macOS architecture (arm64 / x86_64),
// called from Swift through its header.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    jvm()
    // A static framework: Swift imports it as a module (Objective-C header + modulemap), linked into the app and the
    // service by macos/build.sh with -F / -framework (no Xcode, no xcframework needed).
    macosArm64 {
        // The system's sqlite3 (no platform library of Kotlin/Native has it).
        compilations.getByName("main").cinterops.create("sqlite3")
        binaries.framework {
            baseName = "BorderlessShared"
            isStatic = true
        }
    }
    // Same settings for Intel Macs (macos/build.sh picks the target by `uname -m`).
    macosX64 {
        compilations.getByName("main").cinterops.create("sqlite3")
        binaries.framework {
            baseName = "BorderlessShared"
            isStatic = true
        }
    }
    compilerOptions {
        // expect / actual classes (Lock, Locales): Beta, stable enough for a few platform helpers.
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.addAll("kotlin.io.encoding.ExperimentalEncodingApi", "kotlin.uuid.ExperimentalUuidApi",
                     "kotlin.concurrent.atomics.ExperimentalAtomicApi")
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}

// The names of the Android string resources as constants (`S.ev_searching` = "ev_searching"): the shared code names
// its texts, each platform renders them — Android from its resources, the Mac from the strings generated from them.
val stringNames by tasks.registering {
    val src = rootProject.file("android/src/main/res/values/strings.xml")
    val out = layout.buildDirectory.dir("generated/strings/kotlin")
    inputs.file(src)
    outputs.dir(out)
    doLast {
        val names = Regex("""<(?:string|plurals|string-array) name="([A-Za-z0-9_]+)"""").findAll(src.readText())
            .map { it.groupValues[1] }.distinct().sorted().toList()
        val file = out.get().file("app/borderless/S.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(buildString {
            appendLine("// Generated from android/src/main/res/values/strings.xml by the shared module's build: do not edit.")
            appendLine("package app.borderless")
            appendLine()
            appendLine("@Suppress(\"ObjectPropertyName\", \"unused\")")
            appendLine("object S {")
            names.forEach { appendLine("    const val `$it` = \"$it\"") }
            appendLine("}")
        })
    }
}
kotlin.sourceSets.commonMain { kotlin.srcDir(stringNames) }
