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
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation(libs.androidx.security.crypto)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
