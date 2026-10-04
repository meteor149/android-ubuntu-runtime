package ai.meteor.ubuntu.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class UbuntuProcessSupervisor(
    context: Context,
    private val rootAccess: RootAccessController,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var outputJob: Job? = null
    private var exitJob: Job? = null
    private var runningMode: RuntimeMode? = null
    private var chrootPidFile: Path? = null
    private var rootlessPidFile: Path? = null

    suspend fun start(
        runtime: InstalledRuntime,
        mode: RuntimeMode,
        command: UbuntuCommand,
        onLog: (String) -> Unit = {},
        onExit: (Int) -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
        check(process?.isAlive != true) { "The runtime is already running" }
        if (mode == RuntimeMode.Chroot) check(rootAccess.request()) { "Root access is required for chroot" }
        val data = prepareDataDirectories()
        command.bindings.values.forEach { require(Files.isDirectory(it)) { "Bind source must be an existing directory: $it" } }
        configureResolver(runtime.rootfs)

        val child = when (mode) {
            RuntimeMode.Proot -> startProot(runtime, data, command)
            RuntimeMode.Chroot -> startChroot(runtime, data, command)
        }
        process = child
        runningMode = mode
        val groupFile = rootlessPidFile

        outputJob = scope.launch {
            try {
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { rawLine ->
                        val line = rawLine.take(MAX_LOG_LINE_CHARS)
                        Log.i(LOG_TAG, line)
                        onLog(line)
                    }
                }
            } catch (error: IOException) {
                // Android closes process pipes during destroy(), interrupting a blocking read.
                if (currentCoroutineContext().isActive && child.isAlive) {
                    Log.e(LOG_TAG, "Runtime output reader failed", error)
                    child.destroy()
                }
            }
        }
        exitJob = scope.launch {
            val exitCode = child.waitFor()
            // A tracer can exit before its guest descendants; release the whole owned session.
            signalRootlessGroup(groupFile, OsConstants.SIGKILL)
            groupFile?.let(Files::deleteIfExists)
            Log.i(LOG_TAG, "${mode.name} process exited with code $exitCode")
            if (process === child) {
                process = null
                runningMode = null
                chrootPidFile = null
                rootlessPidFile = null
                onExit(exitCode)
            }
        }

        Unit
    }

    /** Executes a command and collects merged stdout/stderr for finite Ubuntu commands. */
    suspend fun execute(
        runtime: InstalledRuntime,
        mode: RuntimeMode,
        command: UbuntuCommand,
    ): UbuntuCommandResult = withContext(Dispatchers.IO) {
        check(process?.isAlive != true) { "The runtime is already running" }
        if (mode == RuntimeMode.Chroot) check(rootAccess.request()) { "Root access is required for chroot" }
        command.bindings.values.forEach { require(Files.isDirectory(it)) { "Bind source must be an existing directory: $it" } }
        configureResolver(runtime.rootfs)
        val data = prepareDataDirectories()
        val child = when (mode) {
            RuntimeMode.Proot -> startProot(runtime, data, command)
            RuntimeMode.Chroot -> startChroot(runtime, data, command)
        }
        process = child
        runningMode = mode
        // Start the watcher before the blocking read, so immediate cancellation still stops the child.
        val pidFile = chrootPidFile
        val rootlessGroup = rootlessPidFile
        val cancellation = CoroutineScope(currentCoroutineContext() + Dispatchers.Default).launch(
            start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED,
        ) {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    if (mode == RuntimeMode.Chroot) {
                        pidFile?.let { rootAccess.execute(buildChrootStopCommand(it)) }
                    } else {
                        signalRootlessGroup(rootlessGroup, OsConstants.SIGTERM)
                        child.destroy()
                    }
                }
            }
        }
        try {
            val output = child.inputStream.bufferedReader().use { it.readText() }
            UbuntuCommandResult(child.waitFor(), output)
        } finally {
            cancellation.cancel()
            withContext(kotlinx.coroutines.NonCancellable) {
                cancellation.join()
                stop()
            }
            child.inputStream.close()
            rootlessGroup?.let(Files::deleteIfExists)
            process = null
            runningMode = null
            chrootPidFile = null
            rootlessPidFile = null
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        val child = process ?: return@withContext
        val mode = runningMode
        val groupFile = rootlessPidFile
        outputJob?.cancel()
        if (mode == RuntimeMode.Chroot) {
            chrootPidFile?.let { pidFile ->
                rootAccess.execute(buildChrootStopCommand(pidFile))
            }
        } else {
            signalRootlessGroup(groupFile, OsConstants.SIGTERM)
            child.destroy()
        }
        val pollAttempts = if (mode == RuntimeMode.Chroot) {
            CHROOT_STOP_POLL_ATTEMPTS
        } else {
            STOP_POLL_ATTEMPTS
        }
        repeat(pollAttempts) {
            if (!child.isAlive && !signalRootlessGroup(groupFile, 0)) {
                exitJob?.join()
                return@withContext
            }
            delay(STOP_POLL_MILLIS)
        }
        child.destroyForcibly()
        signalRootlessGroup(groupFile, OsConstants.SIGKILL)
        exitJob?.join()
        outputJob?.cancel()
        process = null
        runningMode = null
        chrootPidFile = null
    }

    fun close() {
        scope.cancel()
    }

    private fun startProot(
        runtime: InstalledRuntime,
        data: RuntimeDataDirectories,
        command: UbuntuCommand,
    ): Process {
        val nativeDirectory = Paths.get(appContext.applicationInfo.nativeLibraryDir)
        val proot = requireExecutable(nativeDirectory, runtime.manifest.entrypoint.prootLibrary)
        val loader = requireExecutable(nativeDirectory, runtime.manifest.entrypoint.loaderLibrary)
        val hardlinks = appContext.filesDir.toPath().resolve("linux-data/hardlinks")
        Files.createDirectories(hardlinks)
        Os.chmod(hardlinks.toString(), 0x1c0)
        return startRootless(ProcessBuilder(buildProotCommand(runtime, proot, data, command))
            .directory(runtime.runtimeDirectory.toFile())
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment()["HOME"] = data.home.toString()
                environment()["TMPDIR"] = data.temporary.toString()
                environment()["PROOT_TMP_DIR"] = data.temporary.toString()
                environment()["PROOT_L2S_DIR"] = hardlinks.toString()
                environment()["PROOT_LOADER"] = loader.toString()
                environment()["LD_LIBRARY_PATH"] = nativeDirectory.toString()
                environment()["LANG"] = "C.UTF-8"
            }
        )
    }

    private fun startChroot(
        runtime: InstalledRuntime,
        data: RuntimeDataDirectories,
        command: UbuntuCommand,
    ): Process {
        rootlessPidFile = null
        val controlDirectory = appContext.cacheDir.toPath().resolve("chroot")
        Files.createDirectories(controlDirectory)
        val script = controlDirectory.resolve("run-${System.nanoTime()}.sh")
        val pidFile = controlDirectory.resolve("session.pid")
        Files.deleteIfExists(pidFile)
        Files.write(
            script,
            buildChrootScript(runtime, data, pidFile, command).toByteArray(Charsets.UTF_8),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
        chrootPidFile = pidFile
        val command = "exec /system/bin/sh ${shellQuote(script.toString())}"
        return rootAccess.start(command)
    }

    private fun startRootless(builder: ProcessBuilder): Process {
        val control = appContext.cacheDir.toPath().resolve("runtime-processes")
        Files.createDirectories(control)
        val pidFile = control.resolve("session-${System.nanoTime()}.pid")
        rootlessPidFile = pidFile
        val command = builder.command().toList()
        builder.command(listOf(
            "/system/bin/setsid", "-w", "/system/bin/sh", "-c",
            "echo \$\$ > \"\$1\"; shift; exec \"\$@\"",
            "runtime-session", pidFile.toString(),
        ) + command)
        return builder.start()
    }

    /** Each rootless launch owns a separate session, including child processes. */
    private fun signalRootlessGroup(pidFile: Path?, signal: Int): Boolean {
        val pid = try {
            pidFile?.let { String(Files.readAllBytes(it), Charsets.UTF_8).trim().toIntOrNull() }
        } catch (_: NoSuchFileException) {
            null // The exit watcher may have already removed this session's control file.
        } ?: return false
        if (pid <= 1) return false
        return try {
            Os.kill(-pid, signal)
            true
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.ESRCH) throw error
            false
        }
    }

    private fun buildProotCommand(
        runtime: InstalledRuntime,
        proot: Path,
        data: RuntimeDataDirectories,
        command: UbuntuCommand,
    ): List<String> = buildList {
        add(proot.toString())
        add("--kill-on-exit")
        add("--link2symlink")
        add("--sysvipc")
        add("-0")
        add("-r")
        add(runtime.rootfs.toString())
        bindIfReadable(Paths.get("/dev"), "/dev")
        bindIfReadable(Paths.get("/dev/null"), "/dev/null")
        bindIfReadable(Paths.get("/dev/urandom"), "/dev/urandom")
        bindIfReadable(Paths.get("/dev/random"), "/dev/random")
        bindIfReadable(Paths.get("/dev/zero"), "/dev/zero")
        bindIfReadable(Paths.get("/proc"), "/proc")
        bindIfReadable(Paths.get("/sys"), "/sys")
        bind(data.home, "/root")
        bind(data.workspaces, "/workspace")
        command.bindings.forEach { (target, source) -> bind(source, target) }
        add("-w")
        add(command.workingDirectory)
        add("/usr/bin/env")
        add("-i")
        add("HOME=/root")
        add("USER=root")
        add("LOGNAME=root")
        add("SHELL=/bin/bash")
        add("TERM=xterm-256color")
        add("LANG=C.UTF-8")
        add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        command.environment.forEach { (name, value) -> add("$name=$value") }
        addAll(command.arguments)
    }

    private fun buildChrootScript(
        runtime: InstalledRuntime,
        data: RuntimeDataDirectories,
        pidFile: Path,
        command: UbuntuCommand,
    ): String = chrootLaunchScript(
        rootfs = runtime.rootfs,
        home = data.home,
        workspaces = data.workspaces,
        pidFile = pidFile,
        guestCommand = command.arguments.first(),
        arguments = command.arguments.drop(1),
        environment = command.environment,
        bindings = command.bindings,
        workingDirectory = command.workingDirectory,
        appUid = android.os.Process.myUid(),
        appGid = android.system.Os.getgid(),
        appPid = android.os.Process.myPid(),
    )

    private fun MutableList<String>.bindIfReadable(source: Path, target: String) {
        if (Files.exists(source) && Files.isReadable(source)) bind(source, target)
    }

    private fun MutableList<String>.bind(source: Path, target: String) {
        add("-b")
        add("$source:$target")
    }

    private fun prepareDataDirectories(): RuntimeDataDirectories {
        val dataRoot = appContext.filesDir.toPath().resolve("linux-data")
        return RuntimeDataDirectories(
            home = dataRoot.resolve("home"),
            workspaces = dataRoot.resolve("workspaces"),
            temporary = appContext.cacheDir.toPath().resolve("proot"),
        ).also { directories ->
            listOf(
                directories.home,
                directories.workspaces,
                directories.temporary,
            )
                .forEach(Files::createDirectories)
        }
    }

    private fun configureResolver(rootfs: Path) {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val servers = connectivity.getLinkProperties(connectivity.activeNetwork)
            ?.dnsServers
            .orEmpty()
            .mapNotNull { it.hostAddress }
            .distinct()
        if (servers.isEmpty()) return

        val resolvConf = rootfs.resolve("etc/resolv.conf")
        Files.createDirectories(resolvConf.parent)
        Files.deleteIfExists(resolvConf)
        val contents = servers.joinToString(separator = "\n", postfix = "\n") { "nameserver $it" }
        Files.write(resolvConf, contents.toByteArray(Charsets.UTF_8))
    }

    private fun requireExecutable(directory: Path, name: String): Path {
        val path = directory.resolve(name)
        require(Files.isRegularFile(path) && Files.isExecutable(path)) {
            "Required executable is not available in the APK native libraries: $name"
        }
        return path
    }

}

internal fun chrootLaunchScript(
    rootfs: Path,
    home: Path,
    workspaces: Path,
    pidFile: Path,
    guestCommand: String,
    appUid: Int,
    appGid: Int,
    appPid: Int,
    arguments: List<String> = emptyList(),
    environment: Map<String, String> = emptyMap(),
    bindings: Map<String, Path> = emptyMap(),
    workingDirectory: String = "/workspace",
): String = """#!/system/bin/sh
set -u

if [ "${'$'}{1:-}" != "--isolated" ] && command -v unshare >/dev/null 2>&1; then
    if unshare -m /system/bin/true >/dev/null 2>&1; then
        exec unshare -m /system/bin/sh "${'$'}0" --isolated
    fi
fi

if [ "${'$'}{1:-}" = "--isolated" ]; then
    mount --make-rprivate / 2>/dev/null || true
fi

rm -f "${'$'}0"

ROOTFS=${shellQuote(rootfs.toString())}
HOME_SOURCE=${shellQuote(home.toString())}
WORKSPACES_SOURCE=${shellQuote(workspaces.toString())}
SESSION_PID=${shellQuote(pidFile.toString())}
APP_OWNER=${shellQuote("$appUid:$appGid")}
APP_PID=${shellQuote(appPid.toString())}
CHILD_PID=""
WATCHDOG_PID=""

cleanup() {
    trap - EXIT INT TERM HUP
    if [ -n "${'$'}CHILD_PID" ]; then
        kill -TERM "${'$'}CHILD_PID" 2>/dev/null || true
        wait "${'$'}CHILD_PID" 2>/dev/null || true
    fi
    if [ -n "${'$'}WATCHDOG_PID" ]; then
        kill "${'$'}WATCHDOG_PID" 2>/dev/null || true
        wait "${'$'}WATCHDOG_PID" 2>/dev/null || true
    fi
    umount "${'$'}ROOTFS/workspace" 2>/dev/null || true
    ${bindings.keys.reversed().joinToString("\n    ") { "umount " + shellQuote(rootfs.resolve(it.removePrefix("/")).toString()) + " 2>/dev/null || true" }}
    umount "${'$'}ROOTFS/root" 2>/dev/null || true
    umount "${'$'}ROOTFS/sys" 2>/dev/null || true
    umount "${'$'}ROOTFS/proc" 2>/dev/null || true
    umount "${'$'}ROOTFS/dev/pts" 2>/dev/null || true
    umount "${'$'}ROOTFS/dev/shm" 2>/dev/null || true
    umount "${'$'}ROOTFS/dev" 2>/dev/null || true
    chown -R "${'$'}APP_OWNER" "${'$'}ROOTFS" "${'$'}HOME_SOURCE" \
        "${'$'}WORKSPACES_SOURCE" ${bindings.values.joinToString(" ") { shellQuote(it.toString()) }} 2>/dev/null || true
    rm -f "${'$'}SESSION_PID"
}

trap cleanup EXIT
trap 'exit 143' INT TERM HUP

if [ "${'$'}(id -u)" != "0" ]; then
    echo "chroot mode requires uid 0" >&2
    exit 126
fi

echo "${'$'}${'$'}" > "${'$'}SESSION_PID"
(while kill -0 "${'$'}APP_PID" 2>/dev/null; do sleep 2; done; kill -TERM "${'$'}${'$'}") &
WATCHDOG_PID="${'$'}!"
mkdir -p "${'$'}ROOTFS/dev" "${'$'}ROOTFS/proc" "${'$'}ROOTFS/sys" \
    "${'$'}ROOTFS/root" "${'$'}ROOTFS/workspace"
mount --bind /dev "${'$'}ROOTFS/dev" || exit 120
[ ! -d /dev/pts ] || mount --bind /dev/pts "${'$'}ROOTFS/dev/pts" || exit 120
[ ! -d /dev/shm ] || mount --bind /dev/shm "${'$'}ROOTFS/dev/shm" || exit 120
mount -t proc proc "${'$'}ROOTFS/proc" || exit 121
mount --bind /sys "${'$'}ROOTFS/sys" || exit 122
mount --bind "${'$'}HOME_SOURCE" "${'$'}ROOTFS/root" || exit 123
mount --bind "${'$'}WORKSPACES_SOURCE" "${'$'}ROOTFS/workspace" || exit 125

${bindings.entries.joinToString("\n") { (target, source) ->
    val destination = shellQuote(rootfs.resolve(target.removePrefix("/")).toString())
    "mkdir -p $destination\nmount --bind ${shellQuote(source.toString())} $destination || exit 124"
}}

chroot "${'$'}ROOTFS" /usr/bin/env -i \
    HOME=/root USER=root LOGNAME=root SHELL=/bin/bash TERM=xterm-256color LANG=C.UTF-8 \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    ${environment.entries.joinToString(" ") { (name, value) -> shellQuote("$name=$value") }} \
    /bin/sh -c ${shellQuote("cd \"\$1\" && shift && exec \"\$@\"")} ubuntu-command ${shellQuote(workingDirectory)} \
    ${(listOf(guestCommand) + arguments).joinToString(" ", transform = ::shellQuote)} &
CHILD_PID="${'$'}!"
wait "${'$'}CHILD_PID"
STATUS="${'$'}?"
CHILD_PID=""
exit "${'$'}STATUS"
""".trimIndent() + "\n"

internal fun buildChrootStopCommand(pidFile: Path): String = """
PID_FILE=${shellQuote(pidFile.toString())}
[ -r "${'$'}PID_FILE" ] || exit 0
PID="${'$'}(cat "${'$'}PID_FILE")"
case "${'$'}PID" in *[!0-9]*|'') exit 1;; esac
kill -TERM "${'$'}PID"
""".trimIndent()

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private data class RuntimeDataDirectories(
    val home: Path,
    val workspaces: Path,
    val temporary: Path,
)

private const val STOP_POLL_ATTEMPTS = 30
private const val CHROOT_STOP_POLL_ATTEMPTS = 300
private const val STOP_POLL_MILLIS = 100L
private const val MAX_LOG_LINE_CHARS = 4_096
private const val LOG_TAG = "UbuntuRuntime"
