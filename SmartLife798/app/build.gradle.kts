import java.util.Properties
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Optional release signing.
 *
 * `keystore.properties` (git-ignored) may define:
 *   storeFile=/absolute/or/relative/path/to/release.keystore
 *   storePassword=...
 *   keyAlias=...
 *   keyPassword=...
 *
 * When it is absent the release variant still builds, just unsigned, so a fork
 * or CI without secrets is never blocked.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null
// Public certificate pin: release artifacts must use the independent 2.x identity.
val releaseCertificateSha256 = "64cf1b1481361449f4ba4e2cd7c2854f0b8f9ac9c9fdd903017ac2f5459d501d"
if (hasReleaseSigning) {
    check(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
        !keystoreProperties.getProperty(it).isNullOrBlank()
    }) { "Incomplete release signing configuration" }
    val releaseStore = KeyStore.getInstance("JKS").apply {
        rootProject.file(keystoreProperties.getProperty("storeFile")).inputStream().use {
            load(it, keystoreProperties.getProperty("storePassword").toCharArray())
        }
    }
    val alias = keystoreProperties.getProperty("keyAlias")
    check(releaseStore.isKeyEntry(alias)) { "Release signing key missing" }
    val fingerprint = MessageDigest.getInstance("SHA-256")
        .digest(releaseStore.getCertificate(alias).encoded).joinToString("") { "%02x".format(it) }
    check(fingerprint == releaseCertificateSha256) { "Release signing certificate does not match the approved 2.x identity" }
}

val releaseAlipayCallback = "smartlife798sign"
val debugAlipayCallback = "smartlife798debugsign"

android {
    namespace = "com.smartlife798.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.smartlife798.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 94
        versionName = "2.2.14"
        testInstrumentationRunner = "com.smartlife798.app.UpdateInstallInstrumentation"
        vectorDrawables { useSupportLibrary = true }
        manifestPlaceholders["alipayCallbackScheme"] = releaseAlipayCallback
        buildConfigField("String", "ALIPAY_CALLBACK_SCHEME", "\"$releaseAlipayCallback\"")
        buildConfigField("boolean", "RELEASE_UPDATES_ENABLED", "true")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["alipayCallbackScheme"] = debugAlipayCallback
            buildConfigField("String", "ALIPAY_CALLBACK_SCHEME", "\"$debugAlipayCallback\"")
            buildConfigField("boolean", "RELEASE_UPDATES_ENABLED", "false")
            isMinifyEnabled = false
        }
        release {
            // Minification stays off until the R8 path is verified on a device;
            // app/proguard-rules.pro already carries the keeps needed to turn it on.
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildTypes.create("performance") {
        initWith(buildTypes.getByName("release"))
        isDebuggable = false
        signingConfig = signingConfigs.getByName("debug")
        matchingFallbacks += "release"
    }

    testBuildType = if (providers.gradleProperty("performanceTests").orNull == "true") "performance" else "debug"

    compileOptions {
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
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Pure-JVM tests must not explode when a transitive call touches
            // android.util.Log and friends; they assert behaviour, not stubs.
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Expressive APIs; keep prerelease scope limited to Material3.
    implementation("androidx.compose.material3:material3:1.5.0-alpha14")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.android.tools.build:apksig:8.13.0")
    implementation("com.alipay.sdk:alipaysdk-android:15.8.35")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
