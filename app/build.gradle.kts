import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.secrets)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.example"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.aistudio.jarvisv1.bcrftr"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePath =
                System.getenv("KEYSTORE_PATH")
                    ?: "${rootDir}/my-upload-key.jks"

            storeFile = file(keystorePath)
            storePassword = System.getenv("STORE_PASSWORD")
            keyAlias = "upload"
            keyPassword = System.getenv("KEY_PASSWORD")
        }

        create("debugConfig") {
            storeFile =
                file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isCrunchPngs = false
            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )

            signingConfig =
                signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }
}

secrets {
    propertiesFileName = ".env"
    defaultPropertiesFileName = ".env.example"
    ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices {
    missingGoogleServicesStrategy =
        MissingGoogleServicesStrategy.WARN
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(platform(libs.firebase.bom))

    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)

    implementation(libs.converter.moshi)
    implementation(libs.firebase.ai)

    implementation(libs.firebase.appcheck.recaptcha)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.logging.interceptor