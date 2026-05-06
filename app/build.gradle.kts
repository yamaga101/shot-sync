plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "jp.gmail.yamaga101.shotsync"
    compileSdk = 35

    defaultConfig {
        applicationId = "jp.gmail.yamaga101.shotsync"
        minSdk = 28          // S25 Ultra (Android 16) の前世代もカバー
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }

    buildFeatures { compose = true }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
            )
        }
    }
}

dependencies {
    // Compose BOM 経由で揃える
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    // DataStore for settings
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Google Sign-In + Drive
    implementation("com.google.android.gms:play-services-auth:21.4.0")
    implementation("com.google.api-client:google-api-client-android:2.7.0") {
        exclude(group = "org.apache.httpcomponents")
        exclude(module = "guava-jdk5")
    }
    implementation("com.google.apis:google-api-services-drive:v3-rev20240903-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
        exclude(module = "guava-jdk5")
    }
    implementation("com.google.http-client:google-http-client-android:1.45.0") {
        exclude(group = "org.apache.httpcomponents")
    }

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
