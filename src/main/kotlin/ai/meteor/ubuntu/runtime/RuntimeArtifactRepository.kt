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
            readAsset("runtime/ubuntu-image-manifest.json")
        } else {
            engine.copy(available = false)
        }
        val proroot = if ("ubuntu-proroot-manifest.json" in assets.list("runtime").orEmpty()) {
            readAsset("runtime/ubuntu-proroot-manifest.json").takeIf { it.available }
        } else null
        return combineRuntimeManifests(engine, image, proroot)
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
        const val SUPPORTED_SCHEMA_VERSION = 2
    }
}

internal fun combineRuntimeManifests(
    engine: RuntimeManifest,
    image: RuntimeManifest,
    proroot: RuntimeManifest? = null,
): RuntimeManifest {
    if (engine.available && image.available) {
        require(engine.abi == image.abi) { "Ubuntu image and engine ABIs differ" }
        requireNotNull(image.rootfs) { "An available Ubuntu image must declare a rootfs artifact" }
    }
    if (proroot != null) require(proroot.abi == engine.abi) { "proroot and engine ABIs differ" }
    return image.copy(
        available = engine.available && image.available,
        nativeLibraries = engine.nativeLibraries + proroot?.nativeLibraries.orEmpty(),
        entrypoint = engine.entrypoint.copy(guestCommand = image.entrypoint.guestCommand),
    )
}
