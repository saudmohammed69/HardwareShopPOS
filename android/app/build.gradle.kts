plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.hardwareshop.pos"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hardwareshop.pos"
        minSdk = 26
        targetSdk = 35
        versionCode = 26
        versionName = "1.4.21"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"


    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    signingConfigs {
        // Release signing is configured via environment / CI secrets.
        // Never hardcode signing passwords or keys here.
        //
        // For local release builds, configure your release signing
        // credentials separately if required.
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
            isDebuggable = true
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"

        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md"
            )
        }
    }
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")

    // ── Compose BOM ──────────────────────────────────────────────────────────
    val composeBom = platform(libs.androidx.compose.bom)

    implementation(composeBom)
    androidTestImplementation(composeBom)


    // ── Core ─────────────────────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)


    // ── Compose UI ───────────────────────────────────────────────────────────
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    debugImplementation(libs.androidx.compose.ui.tooling)


    // ── Navigation ───────────────────────────────────────────────────────────
    implementation(libs.androidx.navigation.compose)


    // ── Room ─────────────────────────────────────────────────────────────────
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)


    // ── Hilt ─────────────────────────────────────────────────────────────────
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    kapt(libs.androidx.hilt.compiler)

    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)


    // ── Coroutines ───────────────────────────────────────────────────────────
    implementation(libs.kotlinx.coroutines.android)


    // ── DataStore ────────────────────────────────────────────────────────────
    implementation(libs.androidx.datastore.preferences)


    // ── Image loading ────────────────────────────────────────────────────────
    implementation(libs.coil.compose)


    // ── Background work ──────────────────────────────────────────────────────
    implementation(libs.androidx.work.runtime.ktx)


    // ── Biometric authentication ─────────────────────────────────────────────
    implementation(libs.androidx.biometric)


    // ── Encrypted SharedPreferences ──────────────────────────────────────────
    implementation(libs.androidx.security.crypto)


    // ── Serialization ────────────────────────────────────────────────────────
    implementation(libs.gson)


    // ── Logging ──────────────────────────────────────────────────────────────
    implementation(libs.timber)


    // ── Unit tests ───────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)


    // ── Instrumented tests ───────────────────────────────────────────────────
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)


    androidTestImplementation(libs.androidx.room.testing)

    // ── Firebase ─────────────────────────────────────────────────────────────
    val firebaseBom = platform(libs.firebase.bom)

    implementation(firebaseBom)
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)
    implementation(libs.firebase.messaging.ktx)


    // ── Material Design & Splash Screen ──────────────────────────────────────
    implementation(libs.material)
    implementation(libs.androidx.core.splashscreen)
}
