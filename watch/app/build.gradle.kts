import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "dev.wearclaude.watch"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.wearclaude.watch"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing: point Gradle property `claudewear.signing` or env
    // CLAUDEWEAR_SIGNING_PROPERTIES at a keystore.properties file (storeFile,
    // storePassword, keyAlias, keyPassword). Without it the release build is unsigned.
    val signingPath = (project.findProperty("claudewear.signing") as String?)
        ?: System.getenv("CLAUDEWEAR_SIGNING_PROPERTIES")
    val signingProps = Properties()
    val signingFile = signingPath?.let { file(it) }
    val hasSigning = signingFile != null && signingFile.isFile
    if (hasSigning) {
        signingFile!!.inputStream().use { signingProps.load(it) }
    } else {
        logger.warn("ClaudeWear: no signing properties found; the release APK will be unsigned.")
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    applicationVariants.all {
        if (buildType.name == "release") {
            outputs.all {
                (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                    "ClaudeWear-${versionName}.apk"
            }
        }
    }
    lint {
        // lintVitalAnalyzeRelease crashes inside the lint tool itself (AGP 8.7.3); skip it for release builds.
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // Robolectric screenshot tests (docs/images/raw) need merged resources.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)
    implementation(libs.androidx.wear.input)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
