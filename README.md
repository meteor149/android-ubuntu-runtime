# android-ubuntu-runtime

Android library for installing an app-private Ubuntu rootfs, executing commands
through PRoot or root-managed chroot, and managing the existing DSH gateway.
No dependency on Compose, WebView, DSH Mobile, or the image repository is needed
to build this project. The consuming app chooses its image dependency.

Maven artifact: `io.github.meteor149:ubuntu-runtime`.
Repository name and Maven artifact name intentionally differ to preserve existing dependencies.

## Build

Use JDK 21, Android SDK 36, Node.js 24, and Android API 28+ / ARM64 for consumers.
The wrapper uses a Java 17 toolchain for Kotlin/Java compilation, matching the host project.
Build artifacts from source under Linux/WSL2 with Docker available:

```bash
./gradlew buildRuntime
./gradlew testDebugUnitTest assembleRelease
```

The PRoot toolchain and packaging scripts are in `runtime/proot`.
`runtime/versions.env` pins this repository's input versions. Build outputs are
stored in ignored `runtime/dist`. Generated binaries are intentionally not committed.
A diagnostic AAR can be built without artifacts, but cannot be published.

```bash
node tools/generate-runtime-manifest.mjs runtime/dist
node --test tools/generate-runtime-manifest.test.mjs
```

The generator defaults to this repository's component only. An existing manifest
and artifacts can also be supplied with `-PUBUNTU_ENGINE_DIST=/absolute/artifact/path`.

## Publish

```bash
./gradlew publish                 # build/maven-repository
./gradlew publishToMavenLocal
./gradlew publish -PUBUNTU_MAVEN_URL=https://your-repository.example/releases
```

Version/group/artifact properties are in `gradle.properties`. Remote credentials
use `UBUNTU_MAVEN_USERNAME` and `UBUNTU_MAVEN_PASSWORD` environment variables.
Release AAR, sources JAR, POM and Gradle metadata are published. Maven Central publishing uses the configured GitHub Actions secrets.
The build workflow builds real artifacts and uploads a local Maven repository.
The `Publish to Maven Central` workflow runs on a published GitHub Release or
manual dispatch. Release tags must match `v<version>` in `gradle.properties`.
It uses the same secrets as `meteor149/cordis-kotlin`: `MAVEN_CENTRAL_USERNAME`,
`MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY_ID`, `SIGNING_PASSWORD`, `GPG_KEY_CONTENT`.
Signing is done in memory; no private key file is stored in the repository.
The workflow uploads a signed deployment; complete publication in Central Portal
as with cordis-kotlin. First source push does not trigger Central publication.

To invoke the upload task directly, provide the corresponding
`ORG_GRADLE_PROJECT_mavenCentral*` / `ORG_GRADLE_PROJECT_signingInMemory*`
environment variables and run:

```bash
./gradlew publishToMavenCentral -PMAVEN_CENTRAL_PUBLISH=true --no-configuration-cache
```

The release publication includes the AAR, sources, a documentation JAR, POM,
and Gradle metadata. All publishing tasks first validate the real runtime artifacts.

## Integration

See [host configuration and API examples](docs/integration.md).

The `samples/ubuntu-client` project is a Maven-only consumer with its own applicationId.
After publishing this project and `android-ubuntu-image` locally, run:

```bash
./gradlew -p samples/ubuntu-client assembleDebug
```

The sample can also use `-PubuntuRepository=...`, `-PubuntuRuntimeVersion=...`
and `-PubuntuImageVersion=...`. For long-running work the host owns the foreground
service and notification lifetime. proroot binaries are excluded because their
license allows redistribution only inside a complete application package.

## License

Host source code is under [Apache License 2.0](LICENSE). Bundled native programs,
Ubuntu packages and image dependencies retain their upstream licenses.
