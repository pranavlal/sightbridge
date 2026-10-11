plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.sightbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sightbridge"
        minSdk = 36
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
        manifestPlaceholders["appLabel"] = "@string/app_name"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        providers.gradleProperty("sightbridgeAbi").orNull?.let { ndk.abiFilters.add(it) }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        create("offlineCheck") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".offlinecheck"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("qa") {
            initWith(getByName("release"))
            applicationIdSuffix = ".testing"
            versionNameSuffix = "-testing"
            manifestPlaceholders["appLabel"] = "SightBridge Testing"
            matchingFallbacks += listOf("release")
        }
    }
    testBuildType = providers.gradleProperty("sightbridgeTestBuildType").orElse("debug").get()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    androidResources { noCompress += listOf("tflite", "onnx") }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    applicationVariants.all {
        outputs.all {
            val outputImpl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            outputImpl.outputFileName = when (buildType.name) {
                "debug" -> "sightbridge.apk"
                "qa" -> "sightbridge-unsigned.apk"
                else -> "sightBridge-${buildType.name}.apk"
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    implementation("com.google.mlkit:object-detection:17.0.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation("com.herohan:UVCAndroid:1.0.13")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // CameraX dependencies for phone camera fallback
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Meta Wearables DAT SDK Dependencies
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    implementation(libs.mwdat.display)
    debugImplementation(libs.mwdat.mockdevice)
    "offlineCheckImplementation"(libs.mwdat.mockdevice)

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
