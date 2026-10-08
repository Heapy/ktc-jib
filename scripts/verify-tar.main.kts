#!/usr/bin/env kotlinr
// Inspect the real fixture image: kotlinr scripts/verify-tar.main.kts
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0")
@file:DependsOn("org.apache.commons:commons-compress:1.28.0")

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

fun tarEntries(bytes: ByteArray): Map<String, ByteArray> {
    val input = ByteArrayInputStream(bytes)
    val decompressed = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) GZIPInputStream(input) else input
    return TarArchiveInputStream(decompressed).use { tar ->
        buildMap {
            while (true) {
                val entry = tar.nextEntry ?: break
                if (entry.isFile) put(entry.name, tar.readBytes())
            }
        }
    }
}
fun containsZipEntry(bytes: ByteArray, name: String) = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
    generateSequence { zip.nextEntry }.any { it.name == name }
}
val root = __FILE__.canonicalFile.parentFile.parentFile
val archives = root.resolve("build/tasks").walkTopDown().filter { it.isFile && it.name == "image.tar" }.toList()
check(archives.size == 1) { "Expected one fixture image tar; found $archives" }
val archive = archives.single()
val image = tarEntries(archive.readBytes())
val manifest = Json.parseToJsonElement(image.getValue("manifest.json").decodeToString()).jsonArray.single().jsonObject
check(manifest.getValue("RepoTags").jsonArray.map { it.jsonPrimitive.content } == listOf("example/ktc-jib:local"))
val config = Json.parseToJsonElement(image.getValue(manifest.getValue("Config").jsonPrimitive.content).decodeToString()).jsonObject
check(config.getValue("os").jsonPrimitive.content == "linux")
check(config.getValue("architecture").jsonPrimitive.content == "amd64")
val entrypoint = config.getValue("config").jsonObject.getValue("Entrypoint").jsonArray.map { it.jsonPrimitive.content }
check(entrypoint.first() == "java" && entrypoint.last() == "example.MainKt" && entrypoint[1] == "-cp")
val paths = entrypoint[2].split(':')
check(paths.first() == "/app/application.jar")
val content = buildMap {
    for (layer in manifest.getValue("Layers").jsonArray) {
        for ((name, bytes) in tarEntries(image.getValue(layer.jsonPrimitive.content))) put("/" + name.trimStart('.', '/'), bytes)
    }
}
check(paths.all { it in content }) { paths }
check(containsZipEntry(content.getValue("/app/application.jar"), "example/MainKt.class"))
val kotlinJars = paths.filter { "kotlin-stdlib" in it }
check(kotlinJars.isNotEmpty()) { "The app's Kotlin runtime dependency was not packaged" }
check(containsZipEntry(content.getValue(kotlinJars.first()), "kotlin/Unit.class"))
val digest = archive.parentFile.resolve("image.digest").readText().trim()
check(digest.startsWith("sha256:") && digest.length == 71) { digest }
println("Verified $archive: application, runtime classpath, image metadata, digest $digest")
