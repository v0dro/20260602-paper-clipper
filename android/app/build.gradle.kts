import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Apply the Firebase google-services plugin only once google-services.json is present, so the app
// keeps building before Firebase is configured. Once you drop the file into app/, login activates
// automatically (the plugin generates R.string.default_web_client_id, which AuthManager picks up).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// The app talks to the backend proxy (see ../server) instead of Gemini directly, so the Gemini
// key stays off the device. SERVER_URL + PROXY_TOKEN are read from the gitignored local.properties
// and surfaced via BuildConfig. PROXY_TOKEN is a lightweight abuse-gate (it ships in the APK), not
// a high-value secret — the valuable Gemini key lives only on the server.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val serverUrl: String = localProps.getProperty("SERVER_URL", "")
val proxyToken: String = localProps.getProperty("PROXY_TOKEN", "")

android {
    namespace = "com.example.paperclipper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.paperclipper"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "PROXY_TOKEN", "\"$proxyToken\"")
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.coil.compose)
    implementation(libs.cropify)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Firebase Auth + Google Sign-In (Java libs, no Kotlin-metadata concerns).
    // NOTE: the com.google.gms.google-services plugin is intentionally NOT applied yet —
    // add it + google-services.json to activate sign-in. Until then the build works and
    // the Log in action reports that sign-in isn't configured.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.play.services.auth)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
