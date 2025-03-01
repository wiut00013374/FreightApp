plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Starting in Kotlin 2.0, when compose is enabled you must add the Compose Compiler Gradle plugin.
    // The following plugin line is now required:

    id("com.google.gms.google-services")
    alias(libs.plugins.kotlin.compose)
    // Add Hilt if you're using it
    // id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.example.freightapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.freightapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    }
    composeOptions {
        // Ensure the compiler extension version matches the Compose Compiler Gradle plugin version.
        kotlinCompilerExtensionVersion = "1.5.3"
    }
}

// Configuration to resolve dependency conflicts
configurations.all {
    resolutionStrategy {
        // Force specific versions of the protobuf and gRPC libraries
        force("com.google.protobuf:protobuf-javalite:3.22.0")
        force("com.google.protobuf:protobuf-java:3.22.0")
        force("com.google.protobuf:protobuf-java-util:3.22.0")
        force("io.grpc:grpc-protobuf-lite:1.53.0")
        force("io.grpc:grpc-protobuf:1.53.0")
        // Force specific version of com.google.type classes
        force("com.google.api.grpc:proto-google-common-protos:2.15.0")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
    implementation("androidx.activity:activity-compose:1.7.2")
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Firebase dependencies
    implementation(platform("com.google.firebase:firebase-bom:33.9.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx") {
        exclude(group = "com.google.firebase", module = "firebase-common")
    }
    implementation("com.google.firebase:firebase-messaging-ktx")

    // WorkManager for background tasks
    implementation("androidx.work:work-runtime-ktx:2.8.1")

    // OSMDroid for maps
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // RecyclerView and CardView
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("androidx.cardview:cardview:1.0.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation(libs.firebase.inappmessaging.ktx)

    implementation ("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

}
