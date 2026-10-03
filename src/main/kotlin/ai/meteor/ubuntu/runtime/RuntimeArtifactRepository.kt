package ai.meteor.ubuntu.runtime

import android.content.Context
import java.io.InputStream
import kotlinx.serialization.json.Json

class RuntimeArtifactRepository(
    context: Context,
) {
    private val assets = context.applicationContext.assets
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun readManifest(): RuntimeManifest {
        val engine = readAsset("runtime/ubuntu-engine-manifest.json")
        val image = if ("ubuntu-image-manifest.json" in assets.list("runtime").orEmpty()) {
            assets.open("runtime/ubuntu-image-manifest.json").bufferedReader().use {
                json.decodeFromString<UbuntuImageManifest>(it.readText())
            }.also { require(it.schemaVersion == 1) { "Unsupported Ubuntu image descriptor schema" } }
        } else null
        return combineRuntimeManifests(engine, image)
    }

    private fun readAsset(name: String): RuntimeManifest = assets
        .open(name).bufferedReader()
        .use { reader -> json.decodeFromString<RuntimeManifest>(reader.readText()) }
        .also { manifest ->
            require(manifest.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
                "Unsupported runtime manifest schema: ${manifest.schemaVersion}"
            }
        }

    fun openRootfs(manifest: RuntimeManifest): InputStream {
        val rootfs = requireNotNull(manifest.rootfs)
        return assets.open("runtime/${rootfs.file}")
    }

    private companion object {
        const val SUPPORTED_SCHEMA_VERSION = 3
    }
}

internal fun combineRuntimeManifests(
    engine: RuntimeManifest,
    image: UbuntuImageManifest?,
): RuntimeManifest {
    if (image != null) require(image.schemaVersion == 1) { "Unsupported Ubuntu image descriptor schema" }
    if (engine.available && image?.available == true) {
        require(image.architecture == "arm64" && engine.abi == "arm64-v8a") {
            "Ubuntu image architecture and engine ABI differ"
        }
        requireNotNull(image.archive) { "An available Ubuntu image must declare its archive" }
    }
    return engine.copy(
        available = engine.available && image?.available == true,
        runtimeVersion = image?.imageVersion ?: engine.runtimeVersion,
        rootfs = image?.archive,
        sources = engine.sources?.copy(ubuntuImage = image?.source?.ubuntuImage.orEmpty()),
    )
}
