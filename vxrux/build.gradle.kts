version = 1
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlinx-serialization")
}

android {
    namespace = "com.vxrux"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // CRITICAL: Use compileOnly. Do NOT bundle the main app's classes.
    compileOnly("com.lagradost:cloudstream3:1.0.0") 
    
    // Plugin-specific dependencies required by the extractors
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.14.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("dev.whyoleg.cryptography:cryptography-core:0.4.0")
    implementation("com.fleeksoft.ksoup:ksoup:0.1.0")
}
