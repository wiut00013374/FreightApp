
plugins {
    id("com.android.application") version "7.3.0" apply false
    // ...

    // Add the dependency for the Google services Gradle plugin
    id("com.google.gms.google-services") version "4.4.2" apply false
    alias(libs.plugins.kotlin.android) apply false
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
}
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Android Gradle Plugin
        classpath ("com.android.tools.build:gradle:8.8.0")

        // Kotlin Gradle Plugin (2.1.0 or whichever version you need)
        classpath ("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
    }
}
