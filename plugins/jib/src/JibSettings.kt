package io.heapy.ktc.plugins.jib

import org.jetbrains.amper.plugins.Configurable

@Configurable
interface JibSettings {
    val mainClass: String
    val image: String
    val baseImage: String get() = "eclipse-temurin:25-jre"
    val architecture: String get() = "amd64"
    val jvmFlags: List<String> get() = emptyList()
    val arguments: List<String> get() = emptyList()
    val environment: Map<String, String> get() = emptyMap()
    val labels: Map<String, String> get() = emptyMap()
    val ports: List<Int> get() = emptyList()
    val user: String get() = "65532:65532"
    val publishEnabled: Boolean get() = false
    val registryUsernameEnv: String get() = "JIB_REGISTRY_USERNAME"
    val registryPasswordEnv: String get() = "JIB_REGISTRY_PASSWORD"
}
