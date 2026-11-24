plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The version comes from the build (VERSION_NAME / VERSION_CODE); a build without them is 0.0.0.
val appVersionName = System.getenv("VERSION_NAME") ?: "0.0.0"
val appVersionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()

android {
    namespace = "art.kquinn.kuevault"
    compileSdk = 37

    defaultConfig {
        applicationId = "art.kquinn.kuevault"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        // One fixed key, kept locked in the project and unlocked only by the build. Google's sign-in knows the
        // app by this key, so it must never change. For a store release, Google's own store signing is used on top.
        create("fixed") {
            storeFile = file("signing/kuevault.jks")
            storePassword = "kuevault-fixed"
            keyAlias = "kuevault"
            keyPassword = "kuevault-fixed"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
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

    packaging {
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    // Google's own sign-in on the phone. KueVault asks only for the files it makes itself.
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("androidx.core:core:1.19.0")
    // A shared folder on the home computer, reached the way Windows shares folders (SMB 2 and 3).
    implementation("com.hierynomus:smbj:0.14.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    // My things: Google's page scanner (runs in Google Play services, finds and flattens the page), and reading
    // words and barcodes off pictures, all on the phone.
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // Reading a manual's words out of its PDF, on the phone.
    // It brings an older Bouncy Castle for locked PDFs; the newer one already in KueVault does that job.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") { exclude(group = "org.bouncycastle") }
    // The daily warranty check.
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    testImplementation("junit:junit:4.13.2")
}

// A failed check prints its own message in the build log, saying what was wrong.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
