pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri(providers.gradleProperty("ubuntuRepository")
                .getOrElse("../../build/maven-repository"))
            content { includeGroup("io.github.meteor149") }
        }
        maven { url = uri("../../../android-ubuntu-image/build/maven-repository") }
        google()
        mavenCentral()
    }
}
rootProject.name = "ubuntu-client"
