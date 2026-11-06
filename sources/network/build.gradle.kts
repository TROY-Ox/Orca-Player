plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// Network shares (20.2): SMB 2/3, SFTP, FTP and FTPS, WebDAV and DLNA, with seekable Media3 data sources.
android {
    namespace = "com.troy.orcaplayer.sources.network"
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
    api(project(":core:model"))
    implementation(libs.media3.datasource)
    implementation(libs.smbj)
    implementation(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.commons.net)
    implementation(libs.okhttp)
    implementation(libs.tink.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
