plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.licensee)
}

// Release signing comes from environment variables, applied only when ORCA_KEYSTORE_PATH is set.
// ORCA_TEST_KEY=true means the committed test key (app/test-release.keystore) is being used.
fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
val releaseKeystore = env("ORCA_KEYSTORE_PATH")
val testKey = env("ORCA_TEST_KEY") == "true"

android {
    namespace = "com.troy.orcaplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.troy.orcaplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("orca.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("orca.versionName").orNull ?: "0.0.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("standard") {
            dimension = "distribution"
            buildConfigField("boolean", "EXTENDED", "false")
        }
        create("extended") {
            dimension = "distribution"
            applicationIdSuffix = ".extended"
            versionNameSuffix = "-extended"
            buildConfigField("boolean", "EXTENDED", "true")
            proguardFiles("proguard-extended.pro")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = env("ORCA_KEYSTORE_PASSWORD") ?: if (testKey) "orcatest" else null
                keyAlias = env("ORCA_KEY_ALIAS") ?: if (testKey) "orca-test" else null
                keyPassword = env("ORCA_KEY_PASSWORD") ?: if (testKey) "orcatest" else null
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkDependencies = true
        abortOnError = true
        warningsAsErrors = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

licensee {
    allow("Apache-2.0")
    allow("MIT")
    allow("BSD-2-Clause")
    allow("BSD-3-Clause")
    allow("ISC")
    allow("LGPL-2.1-only")
    allow("LGPL-2.1-or-later")
    allow("LGPL-3.0-only")
    allow("LGPL-3.0-or-later")
    allowUrl("https://opensource.org/license/mit") { because("The MIT licence, published under another URL") }
    allowUrl("https://developer.android.com/studio/terms.html") { because("Google Play services Cast SDK, for Chromecast (20.7)") }
    allowUrl("https://www.gnu.org/licenses/old-licenses/lgpl-2.1.en.html") { because("libVLC for Android, LGPL-2.1-or-later, loaded as a separate library (20.8)") }
    allowUrl("https://www.bouncycastle.org/licence.html") { because("The Bouncy Castle licence is the MIT licence") }
}

// Alias used by CI: fails if any dependency of the standard release build is GPL-only
// (or has a licence outside the allow list above).
tasks.register("checkStandardLicences") {
    group = "verification"
    description = "Checks the licences of every dependency in the standard release build."
    dependsOn(tasks.matching { it.name == "licenseeAndroidStandardRelease" || it.name == "licenseeStandardRelease" })
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:model"))
    implementation(project(":core:settings"))
    implementation(project(":core:data"))
    implementation(project(":sources:api"))
    implementation(project(":sources:local"))
    implementation(project(":sources:stremio"))
    implementation(project(":player:engine"))
    implementation(project(":player:subtitles"))
    implementation(project(":tools"))
    implementation(project(":downloads"))
    implementation(project(":vault"))
    implementation(project(":sources:network"))
    implementation(project(":library"))
    implementation(libs.libvlc)
    implementation(libs.play.services.cast)
    implementation(libs.androidx.mediarouter)
    implementation(libs.tink.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.biometric)
    "extendedImplementation"(project(":sources:cloudstream"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.media3.ui.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    ksp(libs.kotlin.metadata)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
