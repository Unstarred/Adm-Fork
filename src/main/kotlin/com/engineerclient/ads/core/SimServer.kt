package com.engineerclient.ads.core

import com.engineerclient.EngineerClient
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.server.IntegratedServer
import net.minecraft.commands.Commands
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.storage.LevelResource
import com.engineerclient.ads.P3Sim
import com.engineerclient.ads.arena.Arena
import com.engineerclient.ads.arena.Blocks
import com.engineerclient.ads.bots.Party
import com.engineerclient.ads.fight.Fight
import com.engineerclient.ads.items.HotbarLayout
import com.engineerclient.ads.items.SimItems
import com.engineerclient.ads.party.AdsServer
import com.engineerclient.ads.party.P3Party

object SimServer {
    @Volatile var server: MinecraftServer? = null
        private set

    @Volatile var devTick: ((MinecraftServer) -> Unit)? = null

    fun isSim(server: MinecraftServer?): Boolean =
        server is IntegratedServer && server.worldData.levelName == SimWorld.NAME &&
            (SimWorld.opening || SimWorld.isBuilt(server.getWorldPath(LevelResource.ROOT)))

    @JvmStatic
    fun isSimLevel(level: Level): Boolean {
        val s = server ?: return false
        return level is ServerLevel && level.server === s
    }

    val level: ServerLevel? get() = server?.overworld()

    val player: ServerPlayer? get() {
        val l = server?.playerList?.players ?: return null
        val me = Minecraft.getInstance().user.profileId
        return l.firstOrNull { it.uuid == me } ?: l.firstOrNull()
    }

    fun run(what: String, block: (MinecraftServer) -> Unit) {
        val s = server ?: return
        s.execute { if (server === s) EngineerClient.safely("p3sim $what") { Sim.asPlayer(null) { block(s) } } }
    }

    fun register() {
        AdsServer.register()
        ServerLifecycleEvents.SERVER_STARTING.register { s ->
            if (isSim(s)) { server = s; EngineerClient.logger.info("[p3sim] sim server starting") }
            SimWorld.opening = false
        }
        ServerLifecycleEvents.SERVER_STARTED.register { s ->
            if (s !== server) return@register
            EngineerClient.safely("p3sim started") {
                rules(s)
                commands(s)
                SimWorld.markBuilt(s.getWorldPath(LevelResource.ROOT))
                Fight.reset(s)
            }
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { s ->
            if (s !== server) return@register
            EngineerClient.safely("p3sim keep hotbars") { Sim.forEachPlayer { HotbarLayout.keep(it, save = true); HotbarLayout.forgetGiven() } }
            EngineerClient.safely("p3sim stopping") { P3Party.stopHosting(unpublish = false); AdsServer.stop(unpublish = false); Fight.stop(); Party.clear(); Sim.clearEntities(); Blocks.restoreAll() }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { s ->
            if (s !== server) return@register
            server = null
            Arena.unload()
            LagRuntime.reset()
        }
        ServerTickEvents.END_SERVER_TICK.register { s ->
            if (s === server) {
                EngineerClient.safely("p3sim tick") { Fight.tick(s) }
                devTick?.let { t -> EngineerClient.safely("ads dev tick") { t(s) } }
                EngineerClient.safely("ads lag") { LagRuntime.settingPreset = P3Sim.lagPresetS.value; LagRuntime.burstRate = P3Sim.lagBurstS.value.toDouble(); LagRuntime.afterTick(s) }
            }
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, s ->
            if (s === server) EngineerClient.safely("p3sim join") { Fight.join(handler.player) }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, s ->
            if (s === server) EngineerClient.safely("p3sim leave") { Fight.leave(handler.player) }
        }
        SimItems.register()
    }

    private fun commands(s: MinecraftServer) {
        s.commands.dispatcher.register(
            Commands.literal("pc").then(Commands.argument("message", StringArgumentType.greedyString()).executes { c ->
                val msg = StringArgumentType.getString(c, "message")
                val name = c.source.player?.gameProfile?.name ?: "You"
                Sim.everyone { Sim.chat("§9Party §8> §b[MVP§4+§b] $name§f: $msg") }
                1
            })
        )
        s.commands.dispatcher.register(
            Commands.literal("gfs").then(Commands.argument("what", StringArgumentType.greedyString()).executes { c ->
                val p = c.source.player ?: return@executes 0
                Sim.asPlayer(p) { getFromSacks(p, StringArgumentType.getString(c, "what")) }
                1
            })
        )
    }

    private val SACK_ITEMS = mapOf(
        "ender_pearl" to Triple("Ender Pearl", { SimItems.PEARLS }, 16),
        "superboom_tnt" to Triple("Superboom TNT", { SimItems.SUPERBOOM }, 64),
        "superboom" to Triple("Superboom TNT", { SimItems.SUPERBOOM }, 64),
    )

    private fun getFromSacks(p: ServerPlayer, what: String) {
        val words = what.trim().split(Regex("\\s+"))
        val amount = words.last().toIntOrNull()
        val key = (if (amount != null) words.dropLast(1) else words).joinToString("_").lowercase().removeSuffix("s")
        val (name, make, max) = SACK_ITEMS[key] ?: run { Sim.chat("§cCouldn't find an item in your Sacks with that name!"); return }
        val want = (amount ?: 64).coerceAtLeast(1)
        val id = SimItems.idOf(make())
        val inv = p.inventory
        var left = want
        for (i in 0 until 36) {
            if (left == 0) break
            val s = inv.getItem(i)
            if (!s.isEmpty && SimItems.idOf(s) == id && s.count < max) { val n = minOf(left, max - s.count); s.grow(n); left -= n }
        }
        for (i in 0 until 36) {
            if (left == 0) break
            if (inv.getItem(i).isEmpty) { val n = minOf(left, max); inv.setItem(i, make().also { it.count = n }); left -= n }
        }
        val moved = want - left
        if (moved == 0) { Sim.chat("§cYou don't have enough space in your inventory!"); return }
        p.inventoryMenu.broadcastChanges()
        Sim.chat("§aMoved §f$moved $name§a from your Sacks to your inventory.")
    }

    private fun rules(s: MinecraftServer) {
        val r: GameRules = s.gameRules
        r.set(GameRules.ADVANCE_TIME, false, s)
        r.set(GameRules.ADVANCE_WEATHER, false, s)
        r.set(GameRules.SPAWN_MOBS, false, s)
        r.set(GameRules.SPAWN_MONSTERS, false, s)
        r.set(GameRules.SPAWN_PHANTOMS, false, s)
        r.set(GameRules.SPAWN_PATROLS, false, s)
        r.set(GameRules.SPAWN_WANDERING_TRADERS, false, s)
        r.set(GameRules.RANDOM_TICK_SPEED, 0, s)
        r.set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0, s)
        r.set(GameRules.FALL_DAMAGE, false, s)
        r.set(GameRules.FIRE_DAMAGE, false, s)
        r.set(GameRules.DROWNING_DAMAGE, false, s)
        r.set(GameRules.KEEP_INVENTORY, true, s)
        r.set(GameRules.IMMEDIATE_RESPAWN, true, s)
        r.set(GameRules.SHOW_DEATH_MESSAGES, false, s)
        r.set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, s)
        r.set(GameRules.MOB_GRIEFING, false, s)
        r.set(GameRules.TNT_EXPLODES, false, s)
        r.set(GameRules.PLAYER_MOVEMENT_CHECK, false, s)
        r.set(GameRules.ELYTRA_MOVEMENT_CHECK, false, s)
        r.set(GameRules.BLOCK_DROPS, false, s)
        r.set(GameRules.ENTITY_DROPS, false, s)
        r.set(GameRules.MOB_DROPS, false, s)
        r.set(GameRules.SEND_COMMAND_FEEDBACK, false, s)
        r.set(GameRules.COMMAND_BLOCK_OUTPUT, false, s)
        r.set(GameRules.LOCATOR_BAR, false, s)
        r.set(GameRules.RESPAWN_RADIUS, 0, s)
    }
}
