buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Meta DAT 0.8 contains newer Kotlin metadata than AGP 8.6's bundled shrinker.
        classpath("com.android.tools:r8:8.13.19")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
}
