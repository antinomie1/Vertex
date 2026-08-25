plugins {
    kotlin("jvm") version "2.4.10"
    id("fabric-loom") version "1.17.19"
}

group = "dev.vertex"
version = "0.1.0-alpha"

base { archivesName = "vertex" }

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

dependencies {
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")
    // Kotlin 支持：FLK 运行时自带 stdlib，不要重复打包
    modImplementation("net.fabricmc:fabric-language-kotlin:${project.property("flk_version")}")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

kotlin {
    jvmToolchain(25)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjvm-default=all")
    }
}
