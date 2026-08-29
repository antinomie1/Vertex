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
        cfg("net.fabricmc.fabric-api:fabric-rendering-v1:${project.property("fabric_rendering")}")
        cfg("net.fabricmc.fabric-api:fabric-lifecycle-events-v1:${project.property("fabric_lifecycle")}")
        cfg("net.fabricmc:fabric-language-kotlin:${project.property("flk_version")}")
    }
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
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

// 无人值守测试：./gradlew runClient -Pvertex.quickplay=<存档名> [-Pvertex.autostop=秒]
val quickplay = providers.gradleProperty("vertex.quickplay")
val autostop = providers.gradleProperty("vertex.autostop")
tasks.withType<JavaExec>().configureEach {
    if (name == "runClient") {
        if (quickplay.isPresent) args(listOf("--quickPlaySingleplayer", quickplay.get()))
        val backend = providers.gradleProperty("vertex.backend")
        if (backend.isPresent) args(listOf("--graphicsBackend", backend.get()))
        if (providers.gradleProperty("vertex.validation").orNull == "true") args("--vulkanValidation")
        if (autostop.isPresent) jvmArgs("-Dvertex.autostop=" + autostop.get())
        val drawMode = providers.gradleProperty("vertex.drawMode")
        if (drawMode.isPresent) jvmArgs("-Dvertex.drawMode=" + drawMode.get())
        val dbg = providers.gradleProperty("vertex.debugReadback")
        if (dbg.isPresent) jvmArgs("-Dvertex.debugReadback=" + dbg.get())
        val perfLog = providers.gradleProperty("vertex.perfLogFrames")
        if (perfLog.isPresent) jvmArgs("-Dvertex.perfLogFrames=" + perfLog.get())
        listOf("perfBaseline", "perfUpdateBaseline", "perfThresholdPercent", "perfGate").forEach { key ->
            providers.gradleProperty("vertex.$key").orNull?.let { jvmArgs("-Dvertex.$key=$it") }
        }
        val pack = providers.gradleProperty("vertex.pack")
        if (pack.isPresent) jvmArgs("-Dvertex.pack=" + pack.get())
        val options = providers.gradleProperty("vertex.options")
        if (options.isPresent) jvmArgs("-Dvertex.options=" + options.get())
    }
}

kotlin {
    jvmToolchain(25)
}
