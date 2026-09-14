import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// Release metadata
//
// versionName: VPLAYER_VERSION_NAME (gradle property or env) wins. Otherwise the
// next patch on the major.minor line of `vplayer.baseVersion`, derived from the
// existing git release tags (v<major>.<minor>.<patch>[-build.<code>]).
//
// versionCode: VPLAYER_VERSION_CODE (gradle property or env) wins. Otherwise
// 1_000_000 + the commit count, so it always increases along the history.
// ---------------------------------------------------------------------------

val versionCodeBase = 1_000_000

fun settingOrNull(name: String): String? =
    (providers.gradleProperty(name).orNull ?: providers.environmentVariable(name).orNull)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

fun git(vararg args: String): String? = runCatching {
    val stdout = ByteArrayOutputStream()
    val result = providers.exec {
        commandLine(listOf("git") + args)
        workingDir = rootDir
        isIgnoreExitValue = true
    }
    val output = result.standardOutput.asText.get().trim()
    val exit = result.result.get().exitValue
    stdout.close()
    if (exit == 0 && output.isNotEmpty()) output else null
}.getOrNull()

val releaseTagPattern = Regex("""^v(\d+)\.(\d+)\.(\d+)(?:-build\.\d+)?$""")
val semverPattern = Regex("""^(\d+)\.(\d+)\.(\d+)$""")

val computedVersionName: String = settingOrNull("VPLAYER_VERSION_NAME") ?: run {
    val base = providers.gradleProperty("vplayer.baseVersion").getOrElse("1.0.0")
    val match = semverPattern.find(base)
        ?: throw GradleException("Expected vplayer.baseVersion to be x.y.z, got: $base")
    val (major, minor, basePatch) = match.destructured.toList().map(String::toInt)
    val highestPatch = (git("tag", "--list")?.lines() ?: emptyList())
        .mapNotNull { releaseTagPattern.find(it.trim()) }
        .map { it.destructured.toList().map(String::toInt) }
        .filter { (tagMajor, tagMinor, _) -> tagMajor == major && tagMinor == minor }
        .maxOfOrNull { (_, _, patch) -> patch }
        ?: (basePatch - 1)
    "$major.$minor.${highestPatch + 1}"
}

val computedVersionCode: Int = settingOrNull("VPLAYER_VERSION_CODE")?.let {
    it.toIntOrNull()?.takeIf { code -> code > 0 }
        ?: throw GradleException("Invalid Android versionCode: $it")
} ?: (versionCodeBase + (git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0))

// Release signing is only wired up when every credential resolves and the
// keystore actually exists; otherwise the release build falls back to the debug
// key so local `assembleRelease` still produces an installable APK.
val releaseStoreFile = settingOrNull("VPLAYER_RELEASE_STORE_FILE")
val releaseStorePassword = settingOrNull("VPLAYER_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = settingOrNull("VPLAYER_RELEASE_KEY_ALIAS")
val releaseKeyPassword = settingOrNull("VPLAYER_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile != null &&
    file(releaseStoreFile).exists() &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "com.seedds.vplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.seedds.vplayer"
        minSdk = 24
        targetSdk = 36
        versionCode = computedVersionCode
        versionName = computedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // Release APKs ship arm64 only; every other ABI is dead weight for the
    // devices this app targets.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties",
                "/META-INF/*.kotlin_module",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
    implementation(libs.media3.ui.compose)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.slf4j.nop)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.cio)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.okhttp)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
