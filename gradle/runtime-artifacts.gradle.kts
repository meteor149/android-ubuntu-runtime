import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest

val dist = providers.gradleProperty("UBUNTU_ENGINE_DIST")
    .map { rootProject.file(it) }.getOrElse(rootProject.file("runtime/dist"))
val fallback = rootProject.file("runtime/manifest/unavailable.json")
val assetsOutput = layout.buildDirectory.dir("generated/runtime/assets")
val jniOutput = layout.buildDirectory.dir("generated/runtime/jniLibs")
val manifestName = "ubuntu-engine-manifest.json"
val names = setOf("libubuntu_proot.so", "libubuntu_proot_loader.so", "libandroid-shmem.so", "libubuntu_talloc.so")

tasks.register("prepareRuntimeAssets") {
    group = "runtime"
    description = "Validates and stages the native Ubuntu runtime artifacts."
    inputs.files(fileTree(dist))
    inputs.file(fallback)
    outputs.dir(assetsOutput)
    outputs.dir(jniOutput)
    doLast {
        val assets = assetsOutput.get().asFile
        val jni = jniOutput.get().asFile
        delete(assets, jni)
        val runtimeAssets = assets.resolve("runtime").apply { mkdirs() }
        jni.mkdirs()
        val sourceManifest = dist.resolve("runtime-manifest.json").takeIf { it.isFile } ?: fallback
        @Suppress("UNCHECKED_CAST")
        val document = (JsonSlurper().parse(sourceManifest) as Map<String, Any?>).toMutableMap()
        check(document["schemaVersion"] == 2) { "Unsupported runtime manifest schema" }
        val available = document["available"] == true
        @Suppress("UNCHECKED_CAST")
        val libraries = (document["nativeLibraries"] as? List<Map<String, String>>).orEmpty()
        check(libraries.map { it["packagedName"] }.toSet() == names || !available) {
            "The engine manifest must contain exactly the required native libraries"
        }
        check(document["rootfs"] == null) { "The engine manifest cannot contain an image archive" }

        fun stage(name: String, expectedHash: String, destination: File) {
            check(name.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid artifact filename: $name" }
            val source = dist.resolve(name)
            check(source.isFile) { "Runtime artifact is missing: $source" }
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual.equals(expectedHash, ignoreCase = true)) { "Runtime artifact checksum mismatch for $name" }
            destination.parentFile.mkdirs()
            source.copyTo(destination, overwrite = true)
        }
        if (available) {
            val abi = document["abi"] as String
            check(abi == "arm64-v8a") { "Unsupported runtime ABI: $abi" }
            libraries.forEach { library ->
                stage(library.getValue("file"), library.getValue("sha256"),
                    jni.resolve("$abi/${library.getValue("packagedName")}"))
            }
        }
        runtimeAssets.resolve(manifestName).writeText(JsonOutput.prettyPrint(JsonOutput.toJson(document)) + "\n")
    }
}

tasks.register("validatePublicationArtifacts") {
    dependsOn("prepareRuntimeAssets")
    doLast {
        val document = JsonSlurper().parse(assetsOutput.get().file("runtime/$manifestName").asFile) as Map<*, *>
        check(document["available"] == true) {
            "Cannot publish $project: runtime artifacts are unavailable. Build or supply the native artifacts first."
        }
    }
}
