plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pvzh.online"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.pvzh.online"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.5.0-r9"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    externalNativeBuild {
        ndkBuild { path = file("src/main/jni/Android.mk") }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Frida Core devkit (~60 MB) is fetched from the official Frida release only
// when it is not already present, so an offline machine can still build.
val prepareFridaDevkit by tasks.registering(Exec::class) {
    val devkit = file("src/main/jni/frida-devkit/libfrida-core.a")
    onlyIf { !devkit.exists() }
    workingDir(rootProject.projectDir)
    commandLine("python", "tools/fetch_frida_devkit.py")
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(prepareFridaDevkit)
}




