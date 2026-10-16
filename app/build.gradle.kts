import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Xray core packaged for Android by the v2rayNG authors (gomobile build of Xray-core). It runs inside the app and
// handles all traffic and server credentials, and release assets on GitHub can be replaced: its SHA-256 is pinned
// here and checked on every build (a mismatch fails it). On a version bump take the hash from a second source.
val xrayCoreVersion = "v26.10.10"
val xrayCoreSha256 = "15db760bfbc24c0492ee997784b48a7b5e1bc5032950d617cd252fc2195f7ac7"
val coreAar = file("libs/libv2ray-$xrayCoreVersion.aar")

fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

val downloadCore = tasks.register("downloadCore") {
    description = "Downloads libv2ray.aar (Xray-core) if it is missing and checks its pinned SHA-256"
    outputs.file(coreAar)
    doLast {
        if (!coreAar.exists()) {
            coreAar.parentFile.mkdirs()
            coreAar.parentFile.listFiles { f -> f.name.startsWith("libv2ray") }?.forEach { it.delete() }
            val url = "https://github.com/2dust/AndroidLibXrayLite/releases/download/$xrayCoreVersion/libv2ray.aar"
            logger.lifecycle("Downloading $url")
            val tmp = File(coreAar.path + ".part")
            uri(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val got = sha256(tmp)
            if (got != xrayCoreSha256) {
                tmp.delete()
                throw GradleException("libv2ray.aar $xrayCoreVersion: SHA-256 $got, expected $xrayCoreSha256 — not using it")
            }
            tmp.renameTo(coreAar)
        }
        val have = sha256(coreAar)
        if (have != xrayCoreSha256) throw GradleException("${coreAar.name}: SHA-256 $have, expected $xrayCoreSha256 — delete it or fix the pin")
    }
}
tasks.named("preBuild") { dependsOn(downloadCore) }

/**
 * Interface languages: English (`res/values`) plus every `res/values-<qualifier>` folder holding a translated
 * strings.xml. Adding a language needs nothing else: the APK keeps it, the system's per-app language list
 * (generated locale config) and the in-app picker show it.
 */
val uiLanguages: List<String> = listOf("en") + (file("src/main/res").listFiles()
    ?.filter { it.isDirectory && it.name.startsWith("values-") && File(it, "strings.xml").exists() }
    ?.map { it.name.removePrefix("values-") }
    ?.sorted() ?: emptyList())

/** Resource qualifier to a BCP 47 tag: "pt-rBR" → "pt-BR", "b+sr+Latn" → "sr-Latn". */
fun languageTag(qualifier: String): String =
    if (qualifier.startsWith("b+")) qualifier.removePrefix("b+").replace('+', '-') else qualifier.replace("-r", "-")

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/** A release is being built (the release signing data is needed only then). */
val releaseRequested = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

/**
 * A signing password: from keystore.properties if it is still written there (the old way), else the environment
 * (BORDERLESS_STORE_PASSWORD / BORDERLESS_KEY_PASSWORD), else the macOS Keychain (generic password, service
 * "borderless-release", account "store" / "key"). Asked only when a release is built.
 */
fun signingPassword(prop: String, env: String, account: String): String? =
    keystoreProps.getProperty(prop)?.takeIf { it.isNotEmpty() }
        ?: System.getenv(env)?.takeIf { it.isNotEmpty() }
        ?: if (!releaseRequested) null else runCatching {
            val p = ProcessBuilder("security", "find-generic-password", "-s", "borderless-release", "-a", account, "-w")
                .redirectErrorStream(false).start()
            p.inputStream.bufferedReader().readText().trim().takeIf { p.waitFor() == 0 && it.isNotEmpty() }
        }.getOrNull()

android {
    namespace = "app.borderless"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.borderless"
        minSdk = 26
        targetSdk = 36
        // versionName: MAJOR.MINOR.PATCH[-beta.N | -rc.N]; versionCode: (MAJOR*10000 + MINOR*100 + PATCH)*100 + N,
        // with N = 99 for the final release of that version (so every build is newer than the one before).
        versionCode = 2000099
        versionName = "2.0.0"
        buildConfigField("String", "XRAY_CORE_VERSION", "\"$xrayCoreVersion\"")
        buildConfigField("String[]", "UI_LANGUAGES", uiLanguages.joinToString(", ", "{", "}") { "\"${languageTag(it)}\"" })
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = signingPassword("storePassword", "BORDERLESS_STORE_PASSWORD", "store")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = signingPassword("keyPassword", "BORDERLESS_KEY_PASSWORD", "key")
            }
        }
    }

    buildTypes {
        debug {
            // `-Pdemo`: a separate app (own data) with made-up servers and statistics, for screenshots.
            if (project.hasProperty("demo")) applicationIdSuffix = ".demo"
            buildConfigField("boolean", "DEMO", project.hasProperty("demo").toString())
        }
        release {
            buildConfigField("boolean", "DEMO", "false")
            // R8 stays off until a release build with it has been tested on the phone: a release must
            // behave exactly like the debug builds that were tested (gomobile/JNI, serialization).
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Never a "release" signed with the debug key: without the release key (or its passwords) it fails.
            val release = signingConfigs.findByName("release")
            if (releaseRequested && (release == null || release.storePassword == null || release.keyPassword == null)) {
                throw GradleException("Release signing is not configured (keystore.properties + passwords from the file, environment or Keychain)")
            }
            signingConfig = release
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // Only our own languages (drops library translations from the APK); the system's list of languages
        // for the app is generated from the same resources (res/resources.properties names the default).
        localeFilters += uiLanguages
        generateLocaleConfig = true
        // libv2ray brings its own full routing lists as assets; the app downloads the categories it needs
        // (core/GeoLists) and ships none.
        ignoreAssetsPattern = "!geoip.dat:!geosite.dat:!geoip-only-cn-private.dat:" +
            "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

if (project.hasProperty("demo")) android.sourceSets.getByName("debug").kotlin.srcDir("src/demo/java")

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.reorderable)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded) { isTransitive = false }
    // Encrypted statistics database (SECURITY.md S16).
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)

    testImplementation(libs.junit)
}
