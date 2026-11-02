import java.net.URI
import java.net.URISyntaxException

plugins {
    // AGP 9+ compiles Kotlin sources itself; org.jetbrains.kotlin.android is no
    // longer applied (and is incompatible with this DSL). The Compose compiler
    // plugin is still applied explicitly.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release enrollment backend URL. Read at configuration time (no secret: a URL is not a
// credential) so the validation task below can be a plain, configuration-cache-friendly check.
val releaseEnrollmentBaseUrlProperty = "ORION_RELEASE_ENROLLMENT_BASE_URL"
val releaseEnrollmentBaseUrl: String? =
    (project.findProperty(releaseEnrollmentBaseUrlProperty) as String?)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv(releaseEnrollmentBaseUrlProperty)?.trim()?.takeIf { it.isNotEmpty() }

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
            // Overridable via `-PORION_ENROLLMENT_BASE_URL=...`. Note: the debug network security
            // config permits cleartext ONLY to 10.0.2.2, so pointing a physical device at a LAN
            // backend needs an https URL (or adding that host to
            // src/debug/res/xml/network_security_config.xml). Defaults to the
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
            // Supplied at build time via -PORION_RELEASE_ENROLLMENT_BASE_URL=https://... or the
            // ORION_RELEASE_ENROLLMENT_BASE_URL environment variable. Never falls back to the
            // emulator/dev URL: validateReleaseEnrollmentBaseUrl (below) fails every release
            // build unless this is a valid https URL.
            buildConfigField(
                "String",
                "ENROLLMENT_BASE_URL",
                "\"${releaseEnrollmentBaseUrl.orEmpty().replace("\\", "\\\\").replace("\"", "\\\"")}\""
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

// Fails any release build (assemble/bundle/install/lint-vital/release unit tests -- everything
// that runs preReleaseBuild) unless the release enrollment URL is a valid https URL. Debug
// builds and :app:testDebugUnitTest never depend on it.
val validateReleaseEnrollmentBaseUrl = tasks.register("validateReleaseEnrollmentBaseUrl") {
    group = "verification"
    description = "Fails unless $releaseEnrollmentBaseUrlProperty is a valid https URL."
    val url = releaseEnrollmentBaseUrl
    val propertyName = releaseEnrollmentBaseUrlProperty
    doLast {
        if (url == null) {
            throw GradleException(
                "Release builds require the enrollment backend URL. Pass " +
                    "-P$propertyName=https://<host> or set the $propertyName environment variable."
            )
        }
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw GradleException("$propertyName is not a valid URL: ${e.reason}")
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw GradleException("$propertyName must use https (got scheme '${uri.scheme}').")
        }
        if (uri.host.isNullOrBlank()) {
            throw GradleException("$propertyName must include a host name.")
        }
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            throw GradleException("$propertyName must not contain user info, a query, or a fragment.")
        }
        logger.lifecycle("Release enrollment backend URL validated: ${uri.scheme}://${uri.host}")
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseEnrollmentBaseUrl)
}
