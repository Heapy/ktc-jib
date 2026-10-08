# ktc-jib

Build JVM application container images with a local Kotlin Toolchain plugin and Jib Core. `jibTar` writes a Docker-compatible image tar and its digest. `jibPublish` explicitly publishes that tar to a registry. Neither command needs a Docker daemon.

Pinned and tested with **Kotlin Toolchain 0.13.0** and **Jib Core 0.28.2**. Apache-2.0 licensed. This is an independent integration, not an official JetBrains or Google plugin.

## Try it

```sh
./kotlin test -m jib
./kotlin do jibTar -m app
kotlinr scripts/verify-tar.main.kts
```

The example deliberately uses `scratch` so image creation and tests need no registry or Docker. **That fixture is not runnable: scratch has no Java runtime.** Change `examples/app/module.yaml` to `baseImage: eclipse-temurin:25-jre` to build a runnable image; the first build then downloads the base image. Select a JRE at least as new as your app's `settings.jvm.release`. Pin production base images by digest for reproducibility.

The CLI prints the image tar location under `build/tasks/`. With a JRE base, load that tar with `docker load --input <path>/image.tar`, then `docker run --rm example/ktc-jib:local`.

## Install into another project

From your consumer project, use the [ktc-plugins installer](https://github.com/Heapy/ktc-plugins):

```sh
./ktc-plugins add Heapy/ktc-jib --branch main --enable-in app
```

Replace `app` with your consumer module path. The root [`ktc-plugin.yaml`](ktc-plugin.yaml)
declares selector `jib`, module `plugins/jib`, and `LICENSE`; the installer registers
and enables the plugin automatically. Commit the generated manifest, lockfile, and vendored
sources. The lockfile pins the resolved commit; use `--commit <full-40-character-SHA>`
instead of `--branch main` to select a specific revision.

For manual installation:

Copy `plugins/jib` into your project and retain this repository's `LICENSE`. This directory is self-contained, with literal Maven dependencies and no shared modules, version catalog, or templates. `ktc-plugin.yaml` is a source-distribution manifest for the companion `ktc-plugins` installer; it is not a Kotlin Toolchain file.

Register the copied module in both lists in `project.yaml`:

```yaml
modules:
  - app
  - plugins/jib
plugins:
  - //plugins/jib
```

Enable it in a JVM app's `module.yaml`:

```yaml
plugins:
  jib:
    enabled: true
    mainClass: com.example.MainKt
    image: ghcr.io/your-org/your-app:1.0.0
    baseImage: eclipse-temurin:25-jre
    architecture: amd64
    jvmFlags: [-Xmx512m]
    arguments: []
    environment:
      APP_MODE: production
    labels:
      org.opencontainers.image.title: your-app
    ports: [8080]
    user: "65532:65532"
```

`mainClass` and `image` are required. Other settings above are optional: the defaults are Temurin 25 JRE, Linux/amd64, empty flags/arguments/environment/labels/ports, and the non-root user `65532:65532`. `architecture` also accepts `arm64`. Environment values are baked into the image and must not contain secrets.

The plugin consumes `module.jar` and `module.runtimeClasspath`, so compilation and dependency resolution happen automatically. The application JAR and runtime dependencies are separate layers. Classpath order is preserved, duplicate filenames are kept at unique paths, and directories (including local dependency classes/resources) are supported. Container timestamps are deterministic. Image identity is reproducible when all input bytes and the base image digest are identical.

## Explicit registry publication

Publication is **disabled by default**, is never registered as a build/check step, and is never skipped by task execution avoidance. To enable it, add these settings to the app's existing `plugins.jib` block:

```yaml
publishEnabled: true
registryUsernameEnv: JIB_REGISTRY_USERNAME
registryPasswordEnv: JIB_REGISTRY_PASSWORD
```

Provide those environment variables through your secret manager or CI secret environment, then explicitly run:

```sh
./kotlin do jibPublish -m app
```

This builds the tar if needed, then pushes it to the configured `image` using HTTPS. It writes `published.digest` only after success. Credentials are read only during publication; settings contain their environment variable **names**, never secret values. Both values are required. No registry push is included in the tests or CI workflow.

## Scope and maintenance

- JVM application JARs and Linux images only; no WAR, Spring Boot executable-JAR interpretation, native-image, Dockerfile, extra-file mapping, multi-platform manifest publication, or automatic main-class detection.
- Public registry base images are supported. Private base-image credentials and Docker credential helpers are not configured by this first version.
- `scratch` is an artifact test fixture; use a compatible JRE base for executable applications. Cross-building architecture-specific native dependencies is the consumer's responsibility.
- The tar is Docker archive format. Registry upload is through Jib Core. Publication is an explicit external side effect; a successful local tar build does not verify registry permissions.
- Toolchain 0.13.0 local plugins cannot be distributed as Maven plugins. Vendor this source module and track updates in your repository. Review upstream dependency releases and run the checks below before updating pins.

## Validation

`./kotlin test -m jib` exercises actual offline tar creation, archive/config metadata, duplicate dependency basenames, deterministic image digests, directory classpaths, bad configuration, missing credentials, and disabled publication. `./kotlin do jibTar -m app` exercises the real Toolchain model and task wiring. `scripts/verify-tar.main.kts` confirms that the fixture image contains the application's class, Kotlin standard library, correct entrypoint, tag, and digest without Docker.

The CI workflow runs those checks on Linux and macOS. Publication behavior is validated before networking; registry uploads and running a JRE-based image require separate integration infrastructure.

Upstream references: [Kotlin Toolchain 0.13.0](https://github.com/JetBrains/kotlin-toolchain/tree/v0.13.0), [Jib Core API and design](https://github.com/GoogleContainerTools/jib/tree/v0.28.2-core/jib-core), [Jib Core 0.28.2 release](https://github.com/GoogleContainerTools/jib/releases/tag/v0.28.2-core).

## Real-project trial: Kotgent (2026-10-05)

Tested in an isolated worktree of Kotgent at `ac1f35a21af210c0579b3536f326da96dace0ebb`, with Kotlin Toolchain 0.13.0. Kotgent's daemon applications are Kotlin/Native, so **this JVM plugin cannot containerize the daemon**. Enabling Jib temporarily on the original `kotgent-macos` application and running `jibTar` failed during Toolchain task-graph construction with `Dependencies for JVM are not calculated`. The Native module configuration was then restored.

A clearly marked validation-only JVM adapter depended on Kotgent's unchanged real `plugins/build-info` module and called `kotlinStringLiteral`, `releaseBuildFrom`, and `generatedBuildInfoSource`. Jib built a Linux/arm64 image from `eclipse-temurin:25-jre` (120,031,232-byte Docker archive). Inspection confirmed the JRE, adapter class/resource, original build-info JAR, Kotlin stdlib, and transitive runtime dependencies. Running the exact extracted application classpath with the host JDK passed the real build-info API and resource assertions. This establishes JVM dependency/resource packaging against real project code; it does not establish daemon support or execution inside Linux.

`jibPublish` also rejected the default disabled setting and deliberately missing credentials through the real Toolchain command. The missing-credential probe used unique environment variable names removed from the child environment, so existing user registry credentials could not permit a push. Both failures left no publication digest.

Docker's availability check timed out after 10 seconds, so the image was not loaded or run in a container. No registry image was published. The base tag was resolved during the trial, not pinned for subsequent reproduction; production builds should pin a digest as described above. Detailed local evidence and reproducible trial scripts are retained in the sibling `ktc-plugin-trials/2026-10-05/kotgent-jib/validation` directory.

## Running verification scripts

The `.main.kts` scripts require JDK 25 and Kotlin 2.4.21+ (`kotlinr` on `PATH`).
Run them with `kotlinr scripts/<name>.main.kts` from the repository root.
The Kotlin Toolchain `./kotlin` command is a separate executable. CI installs the script runner
through `.github/actions/setup-kotlin-script`; the first script run compiles the script and
resolves any pinned Maven dependencies. Later runs use the local script cache.
