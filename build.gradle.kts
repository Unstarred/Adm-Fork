import net.fabricmc.loom.api.LoomGradleExtensionAPI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Fabric Loom is only on Fabric's maven, so it is loaded here rather than through a settings file.
buildscript {
    repositories { maven("https://maven.fabricmc.net/"); mavenCentral(); gradlePluginPortal() }
    dependencies { classpath("net.fabricmc:fabric-loom:1.16-SNAPSHOT") }
}

plugins {
    kotlin("jvm") version "2.4.20"
}

apply(plugin = "net.fabricmc.fabric-loom")

group = "com.engineerclient"
// The release workflow sets EC_VERSION (2026.10.8+abc1234); local builds (ec-build) use GITHUB_REF_NAME.
version = providers.environmentVariable("EC_VERSION").orElse(providers.environmentVariable("GITHUB_REF_NAME").map { it.removePrefix("v") }).getOrElse("dev")

base {
    archivesName.set("adm")
}

// Gradle's standard layout: Kotlin in src/main/kotlin, the Java mixins in src/main/java, resources in src/main/resources.

// Headless tests in src/test/kotlin, fixtures in src/test/resources. They cover each module's pure logic: no Minecraft
// world, no Odin. Fixtures are synthetic; never put recorded data in them (the repo is public). `gradle build` runs them.

// Client gametests in src/gametest/kotlin (their fabric.mod.json in src/gametest/resources): a mod of their own, run with
// `gradlew runClientGametest` in build/gametest and never built into the release jar. They drive ADM itself, so they
// get main's output as well as its classpath. Odin goes in build/gametest/mods, as it does in run/mods.
val gametest: SourceSet = sourceSets.create("gametest") {
    compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output
}

// Loom is applied with apply(plugin = ...), so its extension has no generated accessor: it is reached by name.
val loomRuns = (extensions.getByName("loom") as LoomGradleExtensionAPI).runs

// Minecraft's own launcher starts 26.3 with -XX:StackShadowPages=32 (its version JSON's JVM arguments); Loom's dev
// launch doesn't. Without it the dev client died in jvm.dll during resource reloads, about half of the launches, with no
// hs_err file. A crash dump (2026-11-06) shows why: a Worker-Main thread deep in shaderc (26.3 compiles its shaders
// with it) and then back in the JVM, which writes past the end of the thread's stack.
loomRuns.getByName("client") {
    vmArg("-XX:StackShadowPages=32")
}

loomRuns.create("clientGametest") {
    client()
    name("Client Gametest")
    source(gametest)
    // The client harness starts only with this switch (-Dfabric-api.gametest is the server one). Without it the
    // client sits on the title screen and runs nothing.
    vmArg("-Dfabric.client.gametest")
    vmArg("-Dfabric.client.gametest.modid=adm-gametest")
    vmArg("-XX:StackShadowPages=32")
    // No Vulkan implicit layers. 26.3 opens the Vulkan loader at startup even when it ends up on OpenGL (and even with
    // --graphicsBackend opengl), and the loader then injects every installed implicit layer: screen recorders' and
    // overlays' capture hooks (Medal, OBS, Steam, Epic). Benches compare runs, so nothing outside the game should be
    // in the process.
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

    // xz (LZMA2) for ADR recordings: about half the size of gzip. Pure Java, shipped inside
    // the mod jar.
    implementation("org.tukaani:xz:1.10")
    "include"("org.tukaani:xz:1.10")

    // Odin is a required runtime mod (declared in fabric.mod.json). 0.3.7 for 26.3 is not released
    // yet: compiled against the 26.3 port build (OdinFabric PR #164, CI run 37808382947), which the
    // release workflow downloads into libs/odin; kept out of git.
    compileOnly(files("libs/odin"))

    // Sodium replaces the terrain renderer on every team client. Since 26.2 it culls from inside the
    // level extract, so the POV previews no longer call it; optional at runtime.
    compileOnly("maven.modrinth:sodium:bAZQdGpg")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    processResources {
        // The version is expanded into fabric.mod.json: without it as an input, a version bump alone
        // leaves the processed resources "up to date" and the jar keeps the old version.
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
        // Test classes run in parallel JVMs (one class at a time in each: the pack tests share global state, so not threads in one JVM).
        // A serial run was 30 s of test time; the box is shared, so the forks are few and their heaps capped.
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 4).coerceIn(1, 6)
        maxHeapSize = "1g"
        // The optional pack checks (SamplePackTest, RealPackTest) read these folders; without them as inputs a green run would be
        // reused from the cache when only the packs or the variable changed. Both are skipped when the variable is not set.
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
