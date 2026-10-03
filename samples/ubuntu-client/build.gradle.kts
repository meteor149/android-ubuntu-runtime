plugins {
    id("com.android.application") version "8.10.0"
    kotlin("android") version "2.3.21"
}

android {
    namespace = "ai.meteor.ubuntu.sample"
    compileSdk = 36
    defaultConfig {
        applicationId = "ai.meteor.ubuntu.sample"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    // Executables must be extracted by the final APK packager.
    packaging.jniLibs {
        useLegacyPackaging = true
        keepDebugSymbols += setOf("**/libdsh_proot.so", "**/libdsh_proot_loader.so", "**/libandroid-shmem.so", "**/libdsh_talloc.so")
    }
    androidResources.noCompress += "zst"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }

dependencies {
    implementation("io.github.meteor149:ubuntu-runtime:${providers.gradleProperty("ubuntuRuntimeVersion").getOrElse("0.2.0")}")
    implementation("io.github.meteor149:ubuntu-image:${providers.gradleProperty("ubuntuImageVersion").getOrElse("24.04-1")}")
}
