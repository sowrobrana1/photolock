plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.sowrobrana.photolock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sowrobrana.photolock"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.2.7"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Stable DEBUG signing key, committed on purpose: every GitHub runner
    // otherwise generates its own throwaway debug key, and Android then
    // refuses to install an update over the previous build ("App not
    // installed" — signature mismatch, seen 2026-10-24). This is a DEBUG
    // key only; the future RELEASE key must never be committed.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystores/debug.p12")
            storePassword = "photolock-debug"
            keyAlias = "photolock-debug"
            keyPassword = "photolock-debug"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // Phase 2: enable minify + shrinkResources once the feature set is
            // stable and the release keystore exists (never commit it — see .gitignore).
            isMinifyEnabled = false
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
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    // ProcessLifecycleOwner — drives auto-lock when the app backgrounds.
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Biometrics — BiometricPrompt; host activity is a FragmentActivity.
    implementation("androidx.biometric:biometric:1.1.0")

    // Vault index + break-in log (Room, compiled with KSP).
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Settings (DataStore) + encrypted storage for the PIN verifier.
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Video playback in the viewer (Media3 / ExoPlayer).
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // Coroutines (repository layer)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
