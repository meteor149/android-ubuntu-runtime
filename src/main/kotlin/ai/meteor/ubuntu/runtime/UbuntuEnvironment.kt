package ai.meteor.ubuntu.runtime

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Arguments are passed literally. Use `/bin/bash`, `-lc`, script for shell syntax. */
data class UbuntuCommand(
    val arguments: List<String>,
    val environment: Map<String, String> = emptyMap(),
    val bindings: Map<String, java.nio.file.Path> = emptyMap(),
    val workingDirectory: String = "/workspace",
) {
    init {
        require(arguments.isNotEmpty() && arguments.first().startsWith("/")) {
            "Supply an absolute guest executable followed by its arguments"
        }
        require(workingDirectory.startsWith("/") && '\u0000' !in workingDirectory) { "Invalid guest working directory" }
        bindings.forEach { (target, source) ->
            require(target.startsWith("/") && target != "/" && ':' !in target && '\u0000' !in target &&
                java.nio.file.Paths.get(target).normalize().toString() == target) { "Invalid guest bind target" }
            require(source.isAbsolute && ':' !in source.toString() && '\u0000' !in source.toString()) { "Invalid host bind source" }
        }
        require(arguments.none { '\u0000' in it }) { "Arguments cannot contain NUL" }
        require(environment.all { (name, value) ->
            name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && '\u0000' !in value
        }) { "Invalid guest environment variable" }
    }
}

data class UbuntuCommandResult(val exitCode: Int, val output: String)

/**
 * App-private Ubuntu access without Compose, WebView or DSH lifecycle requirements.
 * Reuse one instance per app. Calls are serialized; the caller owns foreground-service
 * lifetime for long-running work. The image library is discovered through Android assets.
 */
class UbuntuEnvironment(context: Context) {
    private val artifacts = RuntimeArtifactRepository(context.applicationContext)
    private val installer = RootfsInstaller(context.applicationContext, artifacts)
    private val supervisor = UbuntuProcessSupervisor(context.applicationContext, RootAccessController())
    private val mutex = Mutex()

    suspend fun probe(): InstalledRuntime? = mutex.withLock { installer.probe() }

    suspend fun install(
        onProgress: (Float, RuntimeMessage) -> Unit = { _, _ -> },
    ): InstalledRuntime = mutex.withLock { installer.install(onProgress = onProgress) }

    suspend fun execute(
        command: UbuntuCommand,
        mode: RuntimeMode = RuntimeMode.Proot,
    ): UbuntuCommandResult = mutex.withLock {
        val installed = requireNotNull(installer.probe()) { "Install Ubuntu before executing commands" }
        supervisor.execute(installed, mode, command)
    }

    /** Starts a long-running command; the caller decides when its service is ready. */
    suspend fun start(
        command: UbuntuCommand,
        mode: RuntimeMode = RuntimeMode.Proot,
        onLog: (String) -> Unit = {},
        onExit: (Int) -> Unit = {},
    ) = mutex.withLock {
        val installed = requireNotNull(installer.probe()) { "Install Ubuntu before starting commands" }
        supervisor.start(installed, mode, command, onLog, onExit)
    }

    suspend fun stop() = mutex.withLock { supervisor.stop() }
}
