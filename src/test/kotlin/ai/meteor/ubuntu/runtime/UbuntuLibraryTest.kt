package ai.meteor.ubuntu.runtime

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertContains
import kotlinx.serialization.json.Json

class UbuntuLibraryTest {
    private fun manifest(available: Boolean = true, abi: String = "arm64-v8a") = RuntimeManifest(
        schemaVersion = 2, available = available, runtimeVersion = "test-image", abi = abi,
        rootfs = RootfsArtifact("rootfs.tar.zst", "hash", 100, 1000),
        entrypoint = RuntimeEntrypoint("proot", "loader", "proroot", "runtime", "bridge", "linker", "stub", "/bin/bash"),
    )

    private fun image(available: Boolean = true, architecture: String = "arm64") = UbuntuImageManifest(
        schemaVersion = 1, available = available, imageVersion = "image-3", architecture = architecture,
        archive = RootfsArtifact("ubuntu-arm64.tar.zst", "hash", 100, 1000),
        source = UbuntuImageSource("ubuntu:24.04"),
    )

    @Test fun independentlyVersionedArtifactsAreCombined() {
        val engine = manifest().copy(runtimeVersion = "engine-2", rootfs = null,
            nativeLibraries = listOf(NativeArtifact("proot", "proot", "hash")))
        val image = image()
        val combined = combineRuntimeManifests(engine, image)
        assertEquals("image-3", combined.runtimeVersion)
        assertEquals(image.archive, combined.rootfs)
        assertEquals(engine.nativeLibraries, combined.nativeLibraries)
        assertEquals(engine.entrypoint, combined.entrypoint)
        assertEquals(combined, Json.decodeFromString<RuntimeManifest>(Json.encodeToString(combined)))
    }

    @Test fun missingImageIsUnavailableAndMismatchedArchitectureIsRejected() {
        assertFalse(combineRuntimeManifests(manifest(), null).available)
        assertFalse(combineRuntimeManifests(manifest(), image(false)).available)
        assertFailsWith<IllegalArgumentException> { combineRuntimeManifests(manifest(), image(architecture = "amd64")) }
        assertFailsWith<IllegalArgumentException> { combineRuntimeManifests(manifest(), image().copy(archive = null)) }
        assertFailsWith<IllegalArgumentException> { combineRuntimeManifests(manifest(), image().copy(schemaVersion = 2)) }
    }

    @Test fun imageDescriptorContainsNoRuntimeConfiguration() {
        val descriptor = Json.decodeFromString<UbuntuImageManifest>(
            """{"schemaVersion":1,"available":true,"imageVersion":"ubuntu-24.04-1", "architecture":"arm64",
                "archive":{"file":"ubuntu-arm64.tar.zst","sha256":"hash","compressedBytes":100,"minimumFreeBytes":1000},
                "source":{"ubuntuImage":"ubuntu:24.04"}}""",
        )
        assertEquals("ubuntu-24.04-1", combineRuntimeManifests(manifest(), descriptor).runtimeVersion)
    }

    @Test fun commandRejectsInvalidExecutableAndEnvironment() {
        assertFailsWith<IllegalArgumentException> { UbuntuCommand(emptyList()) }
        assertFailsWith<IllegalArgumentException> { UbuntuCommand(listOf("bash")) }
        assertFailsWith<IllegalArgumentException> { UbuntuCommand(listOf("/bin/bash"), mapOf("A=B" to "x")) }
        assertFailsWith<IllegalArgumentException> { UbuntuCommand(listOf("/bin/bash", "\u0000")) }
    }

    @Test fun chrootPreservesLiteralArgumentsAndEnvironment() {
        val path = Paths.get("/test")
        val script = chrootLaunchScript(path, path, path, path, "/bin/bash", 1, 1, 1,
            arguments = listOf("-lc", "printf '%s' \"\$VALUE\""), environment = mapOf("VALUE" to "a'b"))
        assertContains(script, shellQuote("VALUE=a'b"))
        assertContains(script, listOf("/bin/bash", "-lc", "printf '%s' \"\$VALUE\"").joinToString(" ", transform = ::shellQuote))
    }
}
