plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "fr.p308.lanceur"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.p308.lanceur"
        minSdk = 26
        targetSdk = 33 // l'autoradio est trÃ¨s probablement en API 33
        versionCode = 26
        versionName = "0.20"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildTypes {
        release {
            // R8 : code et ressources inutilisÃ©s retirÃ©s, Compose bien plus rapide qu'en debug.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // Tests des calculs sur le PC (sans autoradio) : les appels Android non simulés renvoient une valeur vide.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.github.sceneview:sceneview:2.2.1")
    testImplementation("junit:junit:4.13.2")
}
