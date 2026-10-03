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
              "schemaVersion": 2,
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
                "prorootLibrary": "libproroot.so",
                "prorootRuntimeLibrary": "libproroot-runtime.so",
                "prorootBridgeLibrary": "libproroot-bridge.so",
                "prorootLinkerLibrary": "libproroot-linker.so",
                "prorootStubLoaderLibrary": "libproroot-stub-loader.so",
                "guestCommand": "/bin/bash"
              },
              "sources": {
                "ubuntuImage": "ubuntu:24.04",
                "termuxProotVersion": "5.1.107.89",
                "termuxProotCommit": "proot-commit",
                "termuxPackagesCommit": "packages-commit",
                "prorootVersion": "1.2.8"
              }
            }
            """.trimIndent(),
        )
        assertEquals("1.2.8", manifest.sources?.prorootVersion)
        assertEquals(
            listOf(
                "libproroot.so",
                "libproroot-runtime.so",
                "libproroot-bridge.so",
                "libproroot-linker.so",
                "libproroot-stub-loader.so",
            ),
            manifest.entrypoint.prorootLibraries,
        )
    }
}
