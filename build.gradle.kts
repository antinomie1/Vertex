plugins {
    kotlin("jvm") version "2.4.10"
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
}

group = "dev.vertex"
version = "0.1.0-alpha"

base { archivesName = "vertex" }

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

loom {
    splitEnvironmentSourceSets()

    mods {
        create("vertex") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    // MC 26.1+ 无混淆：无 mappings 声明，依赖无需 remap 配置
    // split 源集：client 编译类路径需单独覆盖（fabric-api 的 WorldRenderEvents 在 client 侧引用）
    // 最小事件模块集（聚合包的 permission-api mixin 与 snapshot-9 不兼容）
    listOf("implementation", "clientImplementation").forEach { cfg ->
        cfg("net.fabricmc:fabric-loader:${project.property("loader_version")}")
        cfg("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")
        cfg("net.fabricmc:fabric-language-kotlin:${project.property("flk_version")}")
    }
 }

tasks.withType<ProcessResources>().configureEach {
    val version = project.version
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)
}

