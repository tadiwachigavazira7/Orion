plugins {
    // AGP 9+ compiles Kotlin sources itself; org.jetbrains.kotlin.android is no
    // longer applied (and is incompatible with this DSL). The Compose compiler
    // plugin is still applied explicitly.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.orion"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.orion"
        // minSdk 24 is an ASSUMPTION for enterprise PDT compatibility — confirm the
        // actual minimum OS version against Zebra/Impinj device documentation before
        // shipping; PDTs in the field can run older Android versions than typical
        // consumer devices.
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Overridable via `-PORION_ENROLLMENT_BASE_URL=...` (e.g. to point a physical
            // device on the LAN at a dev backend instead of the emulator). Defaults to the
            // Android emulator's host-loopback alias -- the verified address of the local
            // FastAPI enrollment backend as seen from inside the emulator, NOT
            // localhost/127.0.0.1 (which inside the emulator refers to the emulator itself).
            val enrollmentBaseUrl =
                (project.findProperty("ORION_ENROLLMENT_BASE_URL") as String?) ?: "http://10.0.2.2:8000"
            buildConfigField("String", "ENROLLMENT_BASE_URL", "\"$enrollmentBaseUrl\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Deliberately empty, not a hardcoded local dev address: a release build with no
            // configured backend falls back to UnconfiguredEnrollmentVerifier (see
            // MainActivity), preserving "honest failure until a real backend is configured"
            // for anything other than debug/emulator builds.
            buildConfigField("String", "ENROLLMENT_BASE_URL", "\"\"")
        }
    }

    // AGP 9's built-in Kotlin compiler derives the Kotlin JVM target from
    // compileOptions below — there is no separate `kotlinOptions`/`kotlin {}`
    // DSL to set once org.jetbrains.kotlin.android is no longer applied.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.core)
    implementation(libs.androidx.datastore.tink)
    implementation(libs.tink.android)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.org.json)
}
