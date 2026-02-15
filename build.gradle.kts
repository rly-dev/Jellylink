plugins {
    kotlin("jvm") version "1.8.22"
    alias(libs.plugins.lavalink)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
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

// ---------------------------------------------------------------------------
// Detekt — static analysis
// ---------------------------------------------------------------------------
detekt {
    config.setFrom(files("$rootDir/detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
    parallel = true
    // Exclude generated code from analysis
    source.setFrom(files("src/main/kotlin"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
        sarif.required.set(false)
    }
    exclude("**/generated/**")
}

// ---------------------------------------------------------------------------
// ktlint — formatting
// ---------------------------------------------------------------------------
ktlint {
    version.set("1.5.0")
    android.set(false)
    outputToConsole.set(true)
    ignoreFailures.set(false)
    filter {
        exclude("**/generated/**")
    }
}

// ---------------------------------------------------------------------------
// Wire lint checks into the build lifecycle
// ---------------------------------------------------------------------------
tasks.named("check") {
    dependsOn("detekt", "ktlintCheck")
}
