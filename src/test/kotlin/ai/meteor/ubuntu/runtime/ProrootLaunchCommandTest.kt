package ai.meteor.ubuntu.runtime

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProrootLaunchCommandTest {
    @Test
    fun commandUsesSupportedProrootOptionsAndSharedRuntimeData() {
        val proroot = Paths.get("/native/libproroot.so")
        val rootfs = Paths.get("/data/runtime/rootfs")
        val home = Paths.get("/data/linux-data/home")
        val workspaces = Paths.get("/data/linux-data/workspaces")
        val temporary = Paths.get("/data/files/proroot-tmp")
        val command = prorootLaunchCommand(
            proroot = proroot,
            rootfs = rootfs,
            home = home,
            workspaces = workspaces,
            temporary = temporary,
            environment = mapOf("TEST_TOKEN" to "token-value"),
            guestCommand = "/bin/bash",
        )

        assertEquals(proroot.toString(), command.first())
        assertTrue(command.windowed(2).contains(listOf("-r", rootfs.toString())))
        assertTrue(command.windowed(2).contains(listOf("-b", "$home:/root")))
        assertTrue(command.windowed(2).contains(listOf("-w", "/workspace")))
        assertTrue("-0" in command)
        assertTrue("--link2symlink" in command)
        assertTrue("PROROOT_TMP_DIR=$temporary" in command)
        assertTrue("TEST_TOKEN=token-value" in command)
        assertEquals("/bin/bash", command.last())
        assertFalse("--kill-on-exit" in command)
        assertFalse("--sysvipc" in command)
    }
}
