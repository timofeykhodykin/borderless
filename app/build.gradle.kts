import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Xray core packaged for Android by the v2rayNG authors (gomobile build of Xray-core).
val xrayCoreVersion = "v26.9.30"
val coreAar = file("libs/libv2ray-$xrayCoreVersion.aar")

val downloadCore = tasks.register("downloadCore") {
    description = "Downloads libv2ray.aar (Xray-core) if it is missing"
    outputs.file(coreAar)
    onlyIf { !coreAar.exists() }
    doLast {
        coreAar.parentFile.mkdirs()
        coreAar.parentFile.listFiles { f -> f.name.startsWith("libv2ray") }?.forEach { it.delete() }
        val url = "https://github.com/2dust/AndroidLibXrayLite/releases/download/$xrayCoreVersion/libv2ray.aar"
        logger.lifecycle("Downloading $url")
        val tmp = File(coreAar.path + ".part")
        uri(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp.renameTo(coreAar)
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

android {
    namespace = "app.borderless"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.borderless"
        minSdk = 26
        targetSdk = 36
        // versionName: MAJOR.MINOR.PATCH[-beta.N | -rc.N]; versionCode: (MAJOR*10000 + MINOR*100 + PATCH)*100 + N,
        // with N = 99 for the final release of that version (so every build is newer than the one before).
        versionCode = 1000099
        versionName = "1.0.0"
        buildConfigField("String", "XRAY_CORE_VERSION", "\"$xrayCoreVersion\"")
        buildConfigField("String[]", "UI_LANGUAGES", uiLanguages.joinToString(", ", "{", "}") { "\"${languageTag(it)}\"" })
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
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
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    testImplementation(libs.junit)
}
