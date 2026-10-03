import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    id("com.android.library") version "8.10.0"
    kotlin("android") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    `maven-publish`
    id("com.vanniktech.maven.publish.base") version "0.34.0"
}

group = providers.gradleProperty("UBUNTU_MAVEN_GROUP").get()
version = providers.gradleProperty("UBUNTU_RUNTIME_VERSION").get()
// Central publishing is opt-in so local builds do not require credentials or signing keys.
if (providers.gradleProperty("MAVEN_CENTRAL_PUBLISH").getOrElse("false").toBoolean()) {
    configure<MavenPublishBaseExtension> {
        publishToMavenCentral()
        signAllPublications()
        coordinates(project.group.toString(), providers.gradleProperty("UBUNTU_ARTIFACT_ID").get(), project.version.toString())
    }
}

apply(from = rootProject.file("gradle/runtime-artifacts.gradle.kts"))

android {
    namespace = "ai.meteor.ubuntu.runtime"
    compileSdk = 36
    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/runtime/assets"))
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/runtime/jniLibs"))
    packaging.jniLibs {
        useLegacyPackaging = true
        keepDebugSymbols += setOf("**/libubuntu_proot.so", "**/libubuntu_proot_loader.so", "**/libandroid-shmem.so", "**/libubuntu_talloc.so")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing.singleVariant("release") { withSourcesJar() }
}
kotlin { jvmToolchain(17) }
tasks.named("preBuild") { dependsOn("prepareRuntimeAssets") }
apply(from = rootProject.file("gradle/publish-ubuntu.gradle.kts"))

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.github.luben:zstd-jni:1.5.7-6@aar")
    testImplementation(kotlin("test-junit"))
    testImplementation("com.github.luben:zstd-jni:1.5.7-6")
}

tasks.register<Exec>("buildRuntime") {
    group = "runtime"
    description = "Builds this repository's runtime artifacts and manifest."
    workingDir(rootDir)
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        commandLine("pwsh", "-NoProfile", "-File", "runtime/build-runtime.ps1")
    } else {
        commandLine("bash", "runtime/build-runtime.sh")
    }
}
