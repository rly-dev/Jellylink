plugins {
    kotlin("jvm") version "1.8.22"
    `java-library`
}

group = "dev.jellylink"
version = "0.1.0"

repositories {
    // Lavalink / Lavaplayer artifacts
    maven("https://maven.lavalink.dev/releases")

    mavenCentral()
}

dependencies {
    // Lavalink plugin API (adjust version to match your Lavalink server)
    compileOnly("dev.arbjerg.lavalink:plugin-api:4.0.8")

    // Lavaplayer (provided by Lavalink at runtime; keep as compileOnly)
    compileOnly("dev.arbjerg:lavaplayer:2.2.2")

    // Spring annotations (provided by Lavalink, but needed for compilation)
    compileOnly("org.springframework.boot:spring-boot-starter-web:3.1.0")

    // JSON types used by the plugin API
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}
