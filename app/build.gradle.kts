plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bridge.applewatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bridge.applewatch"
        minSdk = 34
        targetSdk = 35
        versionCode = 7
        versionName = "1.2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign release builds with debug config by default so CI APKs are immediately sideloadable
            signingConfig = signingConfigs.getByName("debug")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        // Keep the gate meaningful: Error-severity checks (MissingPermission, NewApi,
        // ExportedService, ...) fail the build again, while the Warning-severity
        // dependency/targetSdk checks that this project cannot resolve do not (D-10).
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
    }
}

dependencies {
    // Google Health Connect SDK (Stable 1.1.0)
    implementation("androidx.health.connect:connect-client:1.1.0")

    // Jetpack Compose & Material 3
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // The android.jar used for JVM unit tests contains only *stubs* of org.json:
    // every method returns a default (JSONObject.length() == 0, opt() == null), so a
    // parser test would silently assert against defaults. A real implementation on the
    // test-only classpath makes WebhookPayloadParserTest meaningful; it is not shipped,
    // so the APK still uses the platform's own org.json.
    testImplementation("org.json:json:20240303")

    // Robolectric runs the Android service layer on the JVM so WatchBridgeService
    // logic can be tested without a device.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
