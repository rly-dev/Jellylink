plugins {
    kotlin("jvm") version "1.8.22"
    alias(libs.plugins.lavalink)
}

group = "dev.jellylink"
version = "0.1.0"

lavalinkPlugin {
    name = "jellylink-jellyfin"
    path = "dev.jellylink"
    apiVersion = libs.versions.lavalink.api
    serverVersion = libs.versions.lavalink.server
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

kotlin {
    jvmToolchain(17)
}
