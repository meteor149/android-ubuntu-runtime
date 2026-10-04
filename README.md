# android-ubuntu-runtime

Android library for installing an app-private Ubuntu root filesystem, running
commands through PRoot or root-managed chroot, mounting host directories and
supervising processes. The consuming app chooses its image dependency and owns
its application services and UI.

This development branch publishes `io.github.meteor149:ubuntu-runtime:0.3.0-SNAPSHOT`
to `https://central.sonatype.com/repository/maven-snapshots/`.
The stable Maven Central version remains `0.2.0`.
See [temporary PRoot fixes and their limits](runtime/proot/patches/README.md).

## Build

Use JDK 21, Android SDK 36 and Node.js 24 for descriptor tooling. Runtime consumers
require Android API 28 or newer on ARM64. Kotlin and Java compilation use a Java
17 toolchain. Source builds require Linux/WSL2 and Docker:

```bash
./gradlew buildRuntime
node --test tools/generate-runtime-manifest.test.mjs
./gradlew testDebugUnitTest assembleRelease
```

The PRoot toolchain and packaging scripts are in `runtime/proot`. Input revisions
and the builder image are pinned in `runtime/versions.env`. The builder uses a
generic package namespace; the launcher receives paths from each consuming app.
Generated archives and binaries live in ignored `runtime/dist`. Existing native
artifacts can be supplied with `-PUBUNTU_ENGINE_DIST=/absolute/artifact/path`.
A diagnostic AAR can be built without artifacts, but cannot be published.

This AAR bundles the PRoot launcher, loader, shared-memory library and allocation
library. Native filenames use the `libubuntu_` prefix; this snapshot also applies
the limited compatibility patches documented above. It does not
bundle an Ubuntu filesystem. Source builds support `RuntimeMode.Proot` and
`RuntimeMode.Chroot`; the runtime descriptor uses schema 3. The published 0.2.0
artifact retains its original API and descriptor. The snapshot uses the current
two-backend API and schema 3; consumers of 0.2.0 must adjust their integrations.

## Publication

Versions and Maven coordinates are configured in `gradle.properties`.

```bash
./gradlew publish                 # build/maven-repository
./gradlew publishToMavenLocal
./gradlew publish -PUBUNTU_MAVEN_URL=https://your-repository.example/releases
```

Remote repositories use `UBUNTU_MAVEN_USERNAME` and `UBUNTU_MAVEN_PASSWORD`.
The publication includes an AAR, sources, documentation, POM and Gradle metadata.
Publishing refuses unavailable or checksum-invalid artifacts.

The Maven Central workflow runs on manual dispatch or a published GitHub Release.
Release tags must match `v<version>`. It uses `MAVEN_CENTRAL_USERNAME`,
`MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY_ID`, `SIGNING_PASSWORD` and `GPG_KEY_CONTENT`.
Keys are loaded in memory. The workflow uploads, validates and releases a signed
Central deployment and waits for Central to confirm publication; ordinary source pushes only run the build workflow.

For direct Central publication, provide the corresponding
`ORG_GRADLE_PROJECT_mavenCentral*` and `ORG_GRADLE_PROJECT_signingInMemory*`
environment variables, then run:

```bash
./gradlew publishAndReleaseToMavenCentral -PMAVEN_CENTRAL_PUBLISH=true --no-configuration-cache
```

For snapshot versions, use `publishToMavenCentral` instead. Manual workflow
dispatch selects the correct task based on the version suffix.

## Integration

See [host configuration and API examples](docs/integration.md). The public API
provides installation, finite command execution, long-running process control and
directory bindings. The host owns foreground services and notifications.

The standalone Maven consumer in `samples/ubuntu-client` has its own application
identity and resolves both libraries from Maven Central by default. Run:

```bash
./gradlew -p samples/ubuntu-client assembleDebug
```

For a local repository, pass `-PubuntuRepository=...`. Set `ANDROID_HOME` or create
the sample's local SDK properties. The sample accepts
`-PubuntuRepository=...`, `-PubuntuRuntimeVersion=...` and `-PubuntuImageVersion=...`.
It demonstrates generic Ubuntu commands and caller-supplied environment variables.

## License

Host sources use [Apache License 2.0](LICENSE). Bundled native programs retain their
upstream licenses. The consuming app is responsible for retaining those notices.
