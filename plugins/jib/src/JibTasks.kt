package io.github.ktcplugins.jib

import com.google.cloud.tools.jib.api.Containerizer
import com.google.cloud.tools.jib.api.ImageReference
import com.google.cloud.tools.jib.api.Jib
import com.google.cloud.tools.jib.api.JibContainerBuilder
import com.google.cloud.tools.jib.api.RegistryImage
import com.google.cloud.tools.jib.api.TarImage
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer
import com.google.cloud.tools.jib.api.buildplan.Platform
import com.google.cloud.tools.jib.api.buildplan.Port
import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.CompilationArtifact
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.writeText

@TaskAction
fun buildImageTar(
    @Input application: CompilationArtifact,
    @Input runtimeClasspath: Classpath,
    settings: JibSettings,
    @Output outputDir: Path,
) {
    buildTar(application.artifact, runtimeClasspath.resolvedFiles, settings, outputDir)
}

internal fun buildTar(applicationJar: Path, runtimeFiles: List<Path>, settings: JibSettings, outputDir: Path) {
    val builder = applicationImage(applicationJar, runtimeFiles, settings)
    outputDir.createDirectories()
    val imageTar = outputDir.resolve("image.tar")
    // Never leave a previous successful artifact behind when a new build fails.
    Files.deleteIfExists(imageTar)
    Files.deleteIfExists(outputDir.resolve("image.digest"))
    val result = builder.containerize(
        localCaches(Containerizer.to(TarImage.at(imageTar).named(settings.image)), outputDir),
    )
    outputDir.resolve("image.digest").writeText("${result.digest}\n")
    println("Built ${settings.image}: $imageTar (${result.digest})")
}

internal fun applicationImage(applicationJar: Path, runtimeFiles: List<Path>, settings: JibSettings): JibContainerBuilder {
    validateSettings(settings)
    require(applicationJar.isRegularFile()) { "Application JAR does not exist: $applicationJar" }
    val app = applicationJar.toAbsolutePath().normalize()
    val dependencies = runtimeFiles.map { it.toAbsolutePath().normalize() }.distinct().filter { it != app }
    val builder = Jib.from(settings.baseImage)
        .setCreationTime(Instant.EPOCH)
        .setPlatforms(setOf(Platform(settings.architecture, "linux")))
        .setWorkingDirectory(AbsoluteUnixPath.get("/app"))
        .setUser(settings.user)
        .setEnvironment(settings.environment)
        .setLabels(settings.labels)
        .setExposedPorts(settings.ports.map(Port::tcp).toSet())
        .setProgramArguments(settings.arguments)
    val classpath = mutableListOf("/app/application.jar")
    val dependencyLayer = FileEntriesLayer.builder().setName("runtime dependencies")
    dependencies.forEachIndexed { index, path ->
        require(path.isRegularFile() || path.isDirectory()) { "Runtime classpath entry does not exist: $path" }
        // Include an index to preserve ordering and support duplicate dependency basenames.
        val destination = AbsoluteUnixPath.get("/app/classpath/${index.toString().padStart(4, '0')}/${path.name}")
        require(':' !in path.name) { "Runtime filename cannot contain the JVM classpath separator: ${path.name}" }
        dependencyLayer.addEntryRecursive(path, destination)
        classpath += destination.toString()
    }
    if (dependencies.isNotEmpty()) builder.addFileEntriesLayer(dependencyLayer.build())
    builder.addFileEntriesLayer(
        FileEntriesLayer.builder().setName("application")
            .addEntry(app, AbsoluteUnixPath.get("/app/application.jar")).build(),
    )
    return builder.setEntrypoint(listOf("java") + settings.jvmFlags + listOf("-cp", classpath.joinToString(":"), settings.mainClass))
}

internal fun validateSettings(settings: JibSettings) {
    require(settings.mainClass.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))) {
        "mainClass must be a fully qualified JVM class name"
    }
    require(settings.architecture in setOf("amd64", "arm64")) { "architecture must be amd64 or arm64" }
    require(settings.ports.all { it in 1..65535 }) { "ports must be in 1..65535" }
    require(settings.user.isNotBlank()) { "user cannot be blank" }
    require(!ImageReference.parse(settings.image).isScratch) { "The target image cannot be scratch" }
    ImageReference.parse(settings.baseImage)
}

// Publication has external side effects, so it must always run when explicitly requested.
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun publishImageTar(
    @Input imageTar: Path,
    settings: JibSettings,
    @Output outputDir: Path,
) {
    val target = publicationTarget(settings, System::getenv)
    require(imageTar.isRegularFile()) { "Image tar does not exist: $imageTar" }
    outputDir.createDirectories()
    Files.deleteIfExists(outputDir.resolve("published.digest"))
    val result = publicationImage(imageTar, settings).containerize(localCaches(Containerizer.to(target), outputDir))
    outputDir.resolve("published.digest").writeText("${result.digest}\n")
    println("Published ${settings.image} (${result.digest})")
}

internal fun publicationImage(imageTar: Path, settings: JibSettings): JibContainerBuilder =
    Jib.from(TarImage.at(imageTar)).setPlatforms(setOf(Platform(settings.architecture, "linux")))

internal fun publicationTarget(settings: JibSettings, environment: (String) -> String?): RegistryImage {
    require(settings.publishEnabled) { "Registry publication is disabled. Set publishEnabled: true and explicitly run jibPublish." }
    validateSettings(settings)
    val variableName = Regex("[A-Za-z_][A-Za-z0-9_]*")
    require(settings.registryUsernameEnv.matches(variableName) && settings.registryPasswordEnv.matches(variableName)) {
        "Registry credential settings must contain environment variable names"
    }
    val username = environment(settings.registryUsernameEnv)
    val password = environment(settings.registryPasswordEnv)
    require(!username.isNullOrBlank() && !password.isNullOrBlank()) {
        "Set both ${settings.registryUsernameEnv} and ${settings.registryPasswordEnv} before publication"
    }
    return RegistryImage.named(settings.image).addCredential(username, password)
}

private fun localCaches(containerizer: Containerizer, outputDir: Path): Containerizer = containerizer
    .setApplicationLayersCache(outputDir.resolve("cache/application"))
    .setBaseImageLayersCache(outputDir.resolve("cache/base"))
