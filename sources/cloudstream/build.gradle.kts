// Extended build only (GPL-3.0). Added to :app through "extendedImplementation".
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.troy.orcaplayer.sources.cloudstream"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":sources:api"))
}
