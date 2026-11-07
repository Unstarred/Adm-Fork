import net.fabricmc.loom.api.LoomGradleExtensionAPI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories { maven("https://maven.fabricmc.net/"); mavenCentral(); gradlePluginPortal() }
    dependencies { classpath("net.fabricmc:fabric-loom:1.16-SNAPSHOT") }
}

plugins {
    kotlin("jvm") version "2.4.20"
}

apply(plugin = "net.fabricmc.fabric-loom")

group = "com.engineerclient"
version = providers.environmentVariable("EC_VERSION").orElse(providers.environmentVariable("GITHUB_REF_NAME").map { it.removePrefix("v") }).getOrElse("dev")

base {
    archivesName.set("adm")
}

sourceSets.main {
    java.setSrcDirs(listOf("src"))
    kotlin.setSrcDirs(listOf("src"))
    resources.setSrcDirs(listOf("src/resources"))
}

sourceSets.test {
    java.setSrcDirs(listOf("test"))
    kotlin.setSrcDirs(listOf("test"))
    resources.setSrcDirs(listOf("test/resources"))
}

val gametest: SourceSet = sourceSets.create("gametest") {
    java.setSrcDirs(listOf("gametest"))
    kotlin.setSrcDirs(listOf("gametest"))
    resources.setSrcDirs(listOf("gametest/resources"))
    compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output
}

val loomRuns = (extensions.getByName("loom") as LoomGradleExtensionAPI).runs

loomRuns.getByName("client") {
    vmArg("-XX:StackShadowPages=32")
}

loomRuns.create("clientGametest") {
    client()
    name("Client Gametest")
    source(gametest)
    vmArg("-Dfabric.client.gametest")
    vmArg("-Dfabric.client.gametest.modid=adm-gametest")
    vmArg("-XX:StackShadowPages=32")
    environmentVariable("VK_LOADER_LAYERS_DISABLE", "~implicit~")
    runDir("build/gametest")
}

repositories {
    mavenCentral()
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
}

dependencies {
    "minecraft"("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc:fabric-language-kotlin:1.14.1+kotlin.2.4.20")
    implementation("net.fabricmc.fabric-api:fabric-api:0.162.0+26.3")

    implementation("org.tukaani:xz:1.10")
    "include"("org.tukaani:xz:1.10")

    compileOnly(files("libs/odin"))

    compileOnly("maven.modrinth:sodium:bAZQdGpg")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    processResources {
        inputs.property("version", version)
        filesMatching("fabric.mod.json") {
            expand(mapOf("version" to version))
        }
    }

    compileKotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_25
        }
    }

    named<KotlinCompile>("compileGametestKotlin") {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_25
        }
    }

    test {
        useJUnitPlatform()
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 4).coerceIn(1, 6)
        maxHeapSize = "1g"
        for (name in listOf("ADS_SAMPLE_PACKS", "ADS_REAL_PACKS", "ADS_BLOOD_FIGHTS")) {
            inputs.property(name, providers.environmentVariable(name).orElse(""))
            providers.environmentVariable(name).orNull?.let { dir -> file(dir).takeIf { it.isDirectory }?.let { inputs.dir(it) } }
        }
        inputs.property("adsRealReport", providers.environmentVariable("ADS_REAL_REPORT").orElse(""))
        testLogging {
            events("failed", "skipped")
            showStandardStreams = false
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    compileJava {
        options.release = 25
        options.encoding = "UTF-8"
    }
}
