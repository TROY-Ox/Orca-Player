// Extended build only (GPL-3.0). Added to :app through "extendedImplementation".
// Hosts CloudStream extensions (19): the library they are compiled against, a class loader in a
// separate ":extensions" process, and a client the app uses like any other source.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
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
    api(project(":sources:stremio"))
    // The API surface extensions are compiled against, with the runtime libraries it brings
    // (NiceHttp, OkHttp, Jsoup, Jackson, Rhino, coroutines) at the versions it was built with.
    implementation(libs.cloudstream.library)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
