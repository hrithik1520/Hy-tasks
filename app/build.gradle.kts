plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from env (CI secrets or the committed sideload key, see README).
val keystorePath: String? = System.getenv("HY_KEYSTORE_PATH")

android {
    namespace = "com.hy.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hy.assistant"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("HY_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("HY_VERSION_NAME") ?: "0.1.0-dev"

        ndk {
            // Nothing Phone (3a) Pro and virtually all current phones are arm64.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                // Always optimize native code — a Debug llama.cpp build is ~10x slower.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                cppFlags += listOf("-O3")
            }
        }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("HY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HY_KEY_ALIAS")
                keyPassword = System.getenv("HY_KEY_PASSWORD")
                // Sign with every scheme so all installers / OEM builds accept the APK.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
