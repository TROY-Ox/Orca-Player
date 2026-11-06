plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// The media library (20.1): scanning, name matching and TMDB metadata.
android {
    namespace = "com.troy.orcaplayer.library"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:data"))
    implementation(project(":sources:local"))
    implementation(project(":sources:network"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
