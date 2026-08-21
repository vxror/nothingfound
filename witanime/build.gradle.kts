version = 1
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

android {
    namespace = "com.witanime"
    compileSdk = 34
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll(
            "-XXLanguage:+BreakContinueInInlineLambdas",
            "-Xskip-metadata-version-check"
        )
    }
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    implementation("com.github.Blatzar:NiceHttp:0.4.11")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
