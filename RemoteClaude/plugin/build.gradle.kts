plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
}

group = "com.remoteclaude"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.2")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }

    // Ktor embedded WebSocket server
    // Exclude kotlinx-coroutines transitives: IntelliJ Platform already bundles them
    implementation("io.ktor:ktor-server-netty:3.0.3") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jvm")
    }
    implementation("io.ktor:ktor-server-websockets:3.0.3") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jvm")
    }
    implementation("io.ktor:ktor-server-content-negotiation:3.0.3") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jvm")
    }
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jvm")
    }

    // Ktor client (connects to central server as WS client)
    implementation("io.ktor:ktor-client-cio:3.0.3") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jvm")
    }
    implementation("io.ktor:ktor-client-websockets:3.0.3") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jvm")
    }

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // QR code generation
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")

    // Coroutines provided by IntelliJ Platform - do not add explicitly
}

intellijPlatform {
    pluginConfiguration {
        name = "RemoteClaude"
        version = "1.0.0"
        description = "Control Claude Code sessions from your Android device over WiFi"
        ideaVersion {
            sinceBuild = "252"
            untilBuild = "252.*"
        }
        vendor {
            name = "RemoteClaude"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

// buildSearchableOptions launches a headless IDE sandbox to index Settings search entries.
// It fails on Windows with IDEA 2025.2 due to a coroutines-debug javaagent conflict in the
// platform itself (not our code). This task is optional - the plugin works fully without it.
tasks.named("buildSearchableOptions") {
    enabled = false
}