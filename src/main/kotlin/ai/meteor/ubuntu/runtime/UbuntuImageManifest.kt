package ai.meteor.ubuntu.runtime

import kotlinx.serialization.Serializable

/** Image-only asset contract. Execution settings belong to the runtime. */
@Serializable
data class UbuntuImageManifest(
    val schemaVersion: Int,
    val available: Boolean,
    val imageVersion: String,
    val architecture: String,
    val archive: RootfsArtifact? = null,
    val source: UbuntuImageSource? = null,
)

@Serializable
data class UbuntuImageSource(val ubuntuImage: String)
