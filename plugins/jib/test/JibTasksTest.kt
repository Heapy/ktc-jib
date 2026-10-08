package io.heapy.ktc.plugins.jib

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.cloud.tools.jib.api.Containerizer
import com.google.cloud.tools.jib.api.TarImage
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JibTasksTest {
    @TempDir
    lateinit var temporary: Path

    private fun settings(
        publication: Boolean = false,
        main: String = "example.MainKt",
        portNumbers: List<Int> = listOf(8080),
        credentialVariable: String = "JIB_REGISTRY_USERNAME",
        arch: String = "amd64",
    ): JibSettings = object : JibSettings {
        override val mainClass = main
        override val image = "example/test:local"
        override val baseImage = "scratch"
        override val publishEnabled = publication
        override val ports = portNumbers
        override val registryUsernameEnv = credentialVariable
        override val architecture = arch
        override val jvmFlags = listOf("-Xmx128m")
        override val arguments = listOf("hello")
        override val environment = mapOf("APP_MODE" to "test")
        override val labels = mapOf("org.opencontainers.image.title" to "test")
    }

    private fun jar(path: Path, contents: String): Path {
        path.parent.createDirectories()
        JarOutputStream(Files.newOutputStream(path)).use {
            it.putNextEntry(ZipEntry("payload.txt").apply { time = 0 })
            it.write(contents.toByteArray())
            it.closeEntry()
        }
        return path
    }

    @Test
    fun `offline tar retains duplicate basenames and container configuration`() {
        val app = jar(temporary.resolve("app.jar"), "app")
        val first = jar(temporary.resolve("first/common.jar"), "first")
        val second = jar(temporary.resolve("second/common.jar"), "second")
        val output = temporary.resolve("output")
        buildTar(app, listOf(first, second, app), settings(), output)
        val files = tarEntries(output.resolve("image.tar").readBytes())
        val json = ObjectMapper()
        val manifest = json.readTree(files.getValue("manifest.json"))[0]
        assertEquals("example/test:local", manifest["RepoTags"][0].asText())
        val config = json.readTree(files.getValue(manifest["Config"].asText()))
        assertEquals("amd64", config["architecture"].asText())
        assertEquals("linux", config["os"].asText())
        assertEquals("65532:65532", config["config"]["User"].asText())
        assertEquals("/app", config["config"]["WorkingDir"].asText())
        val entrypoint = config["config"]["Entrypoint"].map { it.asText() }
        assertEquals(listOf("java", "-Xmx128m", "-cp", "/app/application.jar:/app/classpath/0000/common.jar:/app/classpath/0001/common.jar", "example.MainKt"), entrypoint)
        assertEquals("hello", config["config"]["Cmd"][0].asText())
        assertTrue(config["config"]["Env"].any { it.asText() == "APP_MODE=test" })
        assertTrue(config["config"]["ExposedPorts"].has("8080/tcp"))
        val layers = manifest["Layers"].flatMap { tarEntries(files.getValue(it.asText())).keys }
        assertTrue(layers.any { it.endsWith("app/application.jar") })
        assertTrue(layers.any { it.endsWith("app/classpath/0000/common.jar") })
        assertTrue(layers.any { it.endsWith("app/classpath/0001/common.jar") })
        assertTrue(output.resolve("image.digest").readText().matches(Regex("sha256:[0-9a-f]{64}\\n")))
    }

    @Test
    fun `same inputs have the same image digest`() {
        val app = jar(temporary.resolve("app.jar"), "app")
        buildTar(app, emptyList(), settings(), temporary.resolve("one"))
        buildTar(app, emptyList(), settings(), temporary.resolve("two"))
        assertEquals(temporary.resolve("one/image.digest").readText(), temporary.resolve("two/image.digest").readText())
    }

    @Test
    fun `publication source preserves image configuration when loaded from tar`() {
        val app = jar(temporary.resolve("app.jar"), "app")
        val output = temporary.resolve("original")
        val settings = settings(arch = "arm64")
        buildTar(app, emptyList(), settings, output)
        val repacked = temporary.resolve("repacked.tar")
        // Same source path used by publication, with a local tar target to avoid a registry.
        publicationImage(output.resolve("image.tar"), settings).containerize(
            Containerizer.to(TarImage.at(repacked).named("example/repacked:local"))
                .setApplicationLayersCache(temporary.resolve("cache/application"))
                .setBaseImageLayersCache(temporary.resolve("cache/base")),
        )
        val json = ObjectMapper()
        fun config(path: Path): com.fasterxml.jackson.databind.JsonNode {
            val files = tarEntries(path.readBytes())
            val manifest = json.readTree(files.getValue("manifest.json"))[0]
            return json.readTree(files.getValue(manifest["Config"].asText()))
        }
        val originalConfig = config(output.resolve("image.tar"))
        val publishedConfig = config(repacked)
        assertEquals(originalConfig["config"], publishedConfig["config"])
        assertEquals("arm64", publishedConfig["architecture"].asText())
    }

    @Test
    fun `directory classpath entries preserve contents`() {
        val app = jar(temporary.resolve("app.jar"), "app")
        val classes = temporary.resolve("classes").createDirectories()
        classes.resolve("example.txt").writeText("resource")
        val image = applicationImage(app, listOf(classes), settings()).toContainerBuildPlan()
        assertTrue(image.layers.flatMap { (it as com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer).entries }
            .any { it.extractionPath.toString() == "/app/classpath/0000/classes/example.txt" })
    }

    @Test
    fun `publication is denied before reading credentials or making a network request`() {
        var readEnvironment = false
        val error = assertFailsWith<IllegalArgumentException> {
            publicationTarget(settings()) { readEnvironment = true; "secret" }
        }
        assertTrue(error.message!!.contains("disabled"))
        assertFalse(readEnvironment)
    }

    @Test
    fun `missing credentials fail without disclosing provided values`() {
        val error = assertFailsWith<IllegalArgumentException> {
            publicationTarget(settings(publication = true)) { if (it.endsWith("USERNAME")) "sensitive-user" else null }
        }
        assertFalse(error.message!!.contains("sensitive-user"))
        assertTrue(error.message!!.contains("JIB_REGISTRY_PASSWORD"))
    }

    @Test
    fun `invalid credential variable names fail before reading environment`() {
        assertFailsWith<IllegalArgumentException> {
            publicationTarget(settings(publication = true, credentialVariable = "actual:credential")) { error("must not read environment") }
        }
    }

    @Test
    fun `invalid main class port and absent classpath fail before building`() {
        val app = jar(temporary.resolve("app.jar"), "app")
        assertFailsWith<IllegalArgumentException> { applicationImage(app, emptyList(), settings(main = "")) }
        assertFailsWith<IllegalArgumentException> { applicationImage(app, emptyList(), settings(portNumbers = listOf(65536))) }
        assertFailsWith<IllegalArgumentException> { applicationImage(app, listOf(temporary.resolve("absent.jar")), settings()) }
    }

    private fun tarEntries(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        val input = ByteArrayInputStream(bytes)
        val uncompressed = if (bytes.take(2) == listOf(0x1f.toByte(), 0x8b.toByte())) GZIPInputStream(input) else input
        TarArchiveInputStream(uncompressed).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (!entry.isDirectory) put(entry.name, tar.readBytes())
            }
        }
    }
}
