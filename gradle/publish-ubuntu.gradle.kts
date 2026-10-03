import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

val documentationJar = tasks.register<Jar>("documentationJar") {
    archiveClassifier.set("javadoc")
    from(rootProject.file("README.md"))
    from(rootProject.file("docs")) { into("docs") }
}

configure<PublishingExtension> {
    repositories {
        maven {
            name = "Ubuntu"
            url = uri(providers.gradleProperty("UBUNTU_MAVEN_URL")
                .getOrElse(rootProject.layout.buildDirectory.dir("maven-repository").get().asFile.toURI().toString()))
            val user = providers.environmentVariable("UBUNTU_MAVEN_USERNAME").orNull
            val password = providers.environmentVariable("UBUNTU_MAVEN_PASSWORD").orNull
            if (user != null || password != null) {
                credentials {
                    username = user
                    this.password = password
                }
            }
        }
    }
    publications {
        register<MavenPublication>("release") {
            artifactId = providers.gradleProperty("UBUNTU_ARTIFACT_ID").get()
            afterEvaluate { from(components["release"]) }
            artifact(documentationJar)
            pom {
                name.set(project.name)
                inceptionYear.set("2026")
                description.set(if (project.name == "android-ubuntu-runtime")
                    "Android Ubuntu installation and PRoot/chroot execution library"
                else "Versioned Ubuntu ARM64 root filesystem with Node.js and DSH")
                url.set("https://github.com/meteor149/android-ubuntu-runtime")
                licenses {
                    license {
                        name.set("Apache License 2.0 (host code); bundled artifacts retain their upstream licenses")
                        distribution.set("repo")
                        url.set("https://github.com/meteor149/android-ubuntu-runtime/blob/main/LICENSE")
                    }
                }
                developers { developer { id.set("meteor149"); name.set("meteor149") } }
                scm {
                    url.set("https://github.com/meteor149/android-ubuntu-runtime")
                    connection.set("scm:git:https://github.com/meteor149/android-ubuntu-runtime.git")
                    developerConnection.set("scm:git:ssh://git@github.com/meteor149/android-ubuntu-runtime.git")
                }
            }
        }
    }
}

// Never publish a diagnostic placeholder library when the build inputs are absent.
tasks.withType<org.gradle.api.publish.maven.tasks.AbstractPublishToMaven>().configureEach {
    dependsOn("validatePublicationArtifacts")
}
