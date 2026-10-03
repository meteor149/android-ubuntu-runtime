package ai.meteor.ubuntu.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class RuntimeManifestTest {
    @Test
    fun parsesCurrentOfficialPackageSourceMetadata() {
        val manifest = Json.decodeFromString<RuntimeManifest>(
            """
            {
              "schemaVersion": 3,
              "available": true,
              "runtimeVersion": "ubuntu-24.04-1",
              "abi": "arm64-v8a",
              "rootfs": {
                "file": "ubuntu-arm64.tar.zst",
                "sha256": "rootfs-sha256",
                "compressedBytes": 119985274,
                "minimumFreeBytes": 2147483648
              },
              "nativeLibraries": [],
              "entrypoint": {
                "prootLibrary": "libubuntu_proot.so",
                "loaderLibrary": "libubuntu_proot_loader.so",
                "guestCommand": "/bin/bash"
              },
              "sources": {
                "ubuntuImage": "ubuntu:24.04",
                "termuxProotVersion": "5.1.107.89",
                "termuxProotCommit": "proot-commit",
                "termuxPackagesCommit": "packages-commit"
              }
            }
            """.trimIndent(),
        )
        assertEquals(3, manifest.schemaVersion)
        assertEquals("packages-commit", manifest.sources?.termuxPackagesCommit)
        assertEquals("libubuntu_proot.so", manifest.entrypoint.prootLibrary)
        assertEquals("libubuntu_proot_loader.so", manifest.entrypoint.loaderLibrary)
        assertEquals("/bin/bash", manifest.entrypoint.guestCommand)
    }
}
