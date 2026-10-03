package ai.meteor.ubuntu.runtime

enum class RuntimeMode {
    Proot,
    Proroot,
    Chroot,
}

enum class RootAccessState {
    NotRequired,
    Required,
    Checking,
    Granted,
    Denied,
}

enum class RuntimeMessageKind {
    ArtifactsUnavailable,
    RuntimeReady,
    RuntimeNotInstalled,
    Installing,
    VerifyingRootfs,
    ExtractingUbuntu,
    ExtractingEntries,
    InstallComplete,
    Starting,
    Running,
    Stopping,
    Stopped,
    Failed,
}

data class RuntimeMessage(
    val kind: RuntimeMessageKind,
    val count: Int? = null,
)

