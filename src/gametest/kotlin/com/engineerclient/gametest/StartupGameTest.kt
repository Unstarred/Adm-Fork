package com.engineerclient.gametest

import com.engineerclient.EngineerClient
import com.google.gson.JsonParser
import com.odtheking.odin.features.ModuleManager
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.Handle
import java.nio.file.Files
import java.nio.file.Path

class StartupGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        val checks = Checks(context, "startup")
        checks.phase("nothing logged as a problem while starting") {
            for (p in ProblemLog.since(0)) checks.expect("at startup: $p", false)
        }
        checks.phase("every ADM module registered with Odin") {
            val registered = context.onClient { ModuleManager.modules.values.toSet() }
            for (m in EngineerClient.MODULES) checks.expect("${m.name} isn't in Odin's ModuleManager", m in registered)
            checks.note("${EngineerClient.MODULES.size} modules")
        }
        checks.phase("every mixin injected") { mixins(context, checks) }
        checks.finish()
    }

    private val injectorAnnotations = listOf("/injection/", "mixinextras/injector")
    private val accessorAnnotations = listOf("Lorg/spongepowered/asm/mixin/gen/Accessor;", "Lorg/spongepowered/asm/mixin/gen/Invoker;")

    private fun mixins(context: ClientGameTestContext, checks: Checks) {
        val loader = EngineerClient::class.java.classLoader
        val export = context.onClient { mc -> mc.gameDirectory.toPath().resolve(".mixin.out").resolve("class") }
        var handlers = 0
        var mixinCount = 0
        for (config in listOf("engineerclient.mixins.json", "engineerclient.odin.mixins.json")) {
            val json = JsonParser.parseString(loader.getResourceAsStream(config)!!.reader().readText()).asJsonObject
            val pkg = json["package"].asString
            for (name in json.getAsJsonArray("client").map { it.asString }) {
                mixinCount++
                val path = pkg.replace('.', '/') + "/" + name.replace('.', '/') + ".class"
                val mixin = ClassNode().also { ClassReader(loader.getResourceAsStream(path)!!.readBytes()).accept(it, 0) }
                val targets = targetsOf(mixin)
                checks.expect("$name: no @Mixin targets read", targets.isNotEmpty())
                for (target in targets) {
                    val loaded = runCatching { Class.forName(target.replace('/', '.'), false, loader) }
                    if (loaded.isFailure) { checks.expect("$name: target $target doesn't load: ${loaded.exceptionOrNull()}", false); continue }
                    val file = export.resolve("$target.class")
                    if (!Files.exists(file)) { checks.expect("$name: $target was never transformed (no export at $file)", false); continue }
                    handlers += checkTarget(name, target, mixin, file, checks)
                }
            }
        }
        checks.note("$mixinCount mixins, $handlers injector and accessor methods found in their targets")
    }

    private fun targetsOf(mixin: ClassNode): List<String> {
        val ann = (mixin.invisibleAnnotations.orEmpty() + mixin.visibleAnnotations.orEmpty())
            .firstOrNull { it.desc == "Lorg/spongepowered/asm/mixin/Mixin;" } ?: return emptyList()
        val out = mutableListOf<String>()
        val values = ann.values ?: return out
        for (i in values.indices step 2) {
            when (values[i]) {
                "value" -> (values[i + 1] as List<*>).forEach { out += (it as Type).internalName }
                "targets" -> (values[i + 1] as List<*>).forEach { out += (it as String).replace('.', '/') }
            }
        }
        return out
    }

    private fun checkTarget(mixinName: String, target: String, mixin: ClassNode, file: Path, checks: Checks): Int {
        val node = ClassNode().also { ClassReader(Files.readAllBytes(file)).accept(it, 0) }
        val called = HashSet<String>()
        for (m in node.methods) for (insn in m.instructions) {
            when (insn) {
                is MethodInsnNode -> if (insn.owner == target) called += insn.name
                is InvokeDynamicInsnNode -> insn.bsmArgs.forEach { if (it is Handle && it.owner == target) called += it.name }
            }
        }
        val names = node.methods.map { it.name }
        var found = 0
        for (m in mixin.methods) {
            val anns: List<AnnotationNode> = m.invisibleAnnotations.orEmpty() + m.visibleAnnotations.orEmpty()
            if (anns.any { a -> accessorAnnotations.contains(a.desc) }) {
                checks.expect("$mixinName.${m.name}: accessor missing on $target", m.name in names)
                found++
            } else if (anns.any { a -> injectorAnnotations.any { a.desc.contains(it) } }) {
                val merged = names.filter { it.endsWith("$" + m.name) }
                checks.expect("$mixinName.${m.name}: handler not merged into $target", merged.isNotEmpty())
                checks.expect("$mixinName.${m.name}: merged into $target but nothing calls it (injection point not found)",
                    merged.isEmpty() || merged.any { it in called })
                found++
            }
        }
        return found
    }
}
