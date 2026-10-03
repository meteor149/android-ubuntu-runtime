pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        providers.gradleProperty("ubuntuRepository").orNull?.let { repository ->
            maven {
                url = uri(repository)
                content { includeGroup("io.github.meteor149") }
            }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "ubuntu-client"
