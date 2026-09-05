plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Public application identifiers only. Provider credentials belong exclusively on the backend.
fun publicConfig(name: String): String = providers.environmentVariable(name).orNull.orEmpty()
fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val ciDebugKeystorePath = providers.environmentVariable("INTERPRETER_DEBUG_KEYSTORE").orNull
val releaseKeystorePath = providers.environmentVariable("INTERPRETER_RELEASE_KEYSTORE").orNull
val releaseStorePassword = providers.environmentVariable("INTERPRETER_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("INTERPRETER_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("INTERPRETER_RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.interpretertrainer.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.interpretertrainer.app"
        minSdk = 31
        targetSdk = 36
        versionCode = providers.environmentVariable("INTERPRETER_VERSION_CODE").orNull?.toIntOrNull() ?: 100_001
        versionName = providers.environmentVariable("INTERPRETER_VERSION_NAME").orNull
            ?.removePrefix("v")
            ?: "1.0.0-preview.1"

        buildConfigField("String", "AI_BACKEND_URL", quoted(publicConfig("INTERPRETER_BACKEND_URL")))
        buildConfigField("String", "FIREBASE_API_KEY", quoted(publicConfig("FIREBASE_ANDROID_API_KEY")))
        buildConfigField("String", "FIREBASE_APP_ID", quoted(publicConfig("FIREBASE_ANDROID_APP_ID")))
        buildConfigField("String", "FIREBASE_PROJECT_ID", quoted(publicConfig("FIREBASE_PROJECT_ID")))
        resValue("string", "facebook_app_id", publicConfig("FACEBOOK_APP_ID").ifBlank { "0" })
        resValue("string", "facebook_client_token", publicConfig("FACEBOOK_CLIENT_TOKEN").ifBlank { "unconfigured" })
        resValue("string", "fb_login_protocol_scheme", "fb" + publicConfig("FACEBOOK_APP_ID").ifBlank { "0" })

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        getByName("debug") {
            if (!ciDebugKeystorePath.isNullOrBlank()) {
                storeFile = file(ciDebugKeystorePath)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        htmlReport = true
        sarifReport = true
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.facebook.android:facebook-login:18.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.10.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")
    implementation("androidx.media3:media3-common:1.11.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
