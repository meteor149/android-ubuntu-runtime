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

    @Test fun independentlyVersionedArtifactsAreCombined() {
        val engine = manifest().copy(runtimeVersion = "engine-2", rootfs = null,
            nativeLibraries = listOf(NativeArtifact("proot", "proot", "hash")))
        val image = manifest().copy(runtimeVersion = "image-3", nativeLibraries = emptyList())
        val combined = combineRuntimeManifests(engine, image)
        assertEquals("image-3", combined.runtimeVersion)
        assertEquals(image.rootfs, combined.rootfs)
        assertEquals(engine.nativeLibraries, combined.nativeLibraries)
        assertEquals(combined, Json.decodeFromString<RuntimeManifest>(Json.encodeToString(combined)))
    }

    @Test fun missingImageIsUnavailableAndMismatchedAbiIsRejected() {
        assertFalse(combineRuntimeManifests(manifest(), manifest(false)).available)
        assertFailsWith<IllegalArgumentException> { combineRuntimeManifests(manifest(), manifest(abi = "x86_64")) }
        assertFailsWith<IllegalArgumentException> { combineRuntimeManifests(manifest(), manifest().copy(rootfs = null)) }
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
