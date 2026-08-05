import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Reads app/google-services.json. The build fails with a clear message until you add it;
    // see the setup section of the README.
    alias(libs.plugins.google.services)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

// The Maps SDK key stays out of git; see README for how to obtain one.
val mapsApiKey: String = localProperties.getProperty("MAPS_API_KEY").orEmpty()

// `./gradlew installDebug -Pmaptalk.emulator=true` → Firebase emulators.
// Default Debug → on-device local demo (no network). Release → live Firebase.
val useFirebaseEmulator: Boolean =
    (project.findProperty("maptalk.emulator")
        ?: localProperties.getProperty("MAPTALK_EMULATOR")
        ?: "false").toString().toBoolean()

val maptalkModeOverride: String? =
    (project.findProperty("maptalk.mode") ?: localProperties.getProperty("MAPTALK_MODE"))
        ?.toString()

android {
    namespace = "app.maptalk"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.maptalk"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["mapsApiKey"] = mapsApiKey
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "USE_FIREBASE_EMULATOR", useFirebaseEmulator.toString())
        // Cloudflare Worker that writes compressed JPEGs into R2 (see workers/media).
        buildConfigField(
            "String",
            "MAPTALK_MEDIA_UPLOAD_URL",
            "\"https://maptalk-media.hhypkfpshg.workers.dev/v1/images\"",
        )
    }

    buildTypes {
        debug {
            val mode = when {
                useFirebaseEmulator -> "emulator"
                maptalkModeOverride != null -> maptalkModeOverride
                else -> "local"
            }
            buildConfigField("String", "MAPTALK_MODE", "\"$mode\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val mode = maptalkModeOverride ?: "live"
            buildConfigField("String", "MAPTALK_MODE", "\"$mode\"")
            buildConfigField("boolean", "USE_FIREBASE_EMULATOR", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(platform(libs.firebase.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.messaging)
    implementation(libs.coroutines.play.services)

    implementation(libs.maps.compose)
    implementation(libs.play.services.location)
    implementation(libs.geofire.common)
    implementation(libs.androidx.exifinterface)
    implementation(libs.coil.compose)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)

    // The cross-device suite: see scripts/cross-device-qa.sh.
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.coroutines.test)
}
