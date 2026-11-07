package com.engineerclient.ads.core

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Relative
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import com.engineerclient.ads.fight.BossWither

object Sim {
    const val TAG = "p3sim"

    val level: ServerLevel get() = SimServer.level ?: error("sim not running")

    val player: ServerPlayer? get() = acting ?: SimServer.player

    val acting: ServerPlayer? get() = actor?.takeIf { SimServer.server?.isSameThread == true }
    private var actor: ServerPlayer? = null

    private var all = false
    val toAll: Boolean get() = all && SimServer.server?.isSameThread == true

    fun players(): List<ServerPlayer> = SimServer.server?.playerList?.players?.toList().orEmpty()

    val host: ServerPlayer? get() = SimServer.player

    fun isHost(p: ServerPlayer?) = p != null && (p === SimServer.player || p.uuid == SimServer.server?.singleplayerProfile?.id())

    fun <T> asPlayer(p: ServerPlayer?, toAll: Boolean = false, run: () -> T): T {
        val was = actor; val wasAll = all
        actor = p; all = toAll
        try { return run() } finally { actor = was; all = wasAll }
    }

    @JvmStatic fun enterPacket(p: ServerPlayer) {
        val s = SimServer.server ?: return
        if (!s.isSameThread || p.level().server !== s) return
        actor = p; all = false
    }

    @JvmStatic fun leavePacket(p: ServerPlayer) { if (actor === p && SimServer.server?.isSameThread == true) actor = null }

    fun clearActor() { actor = null; all = false }

    fun forEachPlayer(run: (ServerPlayer) -> Unit) { for (p in players()) asPlayer(p) { run(p) } }

    fun <T> everyone(run: () -> T): T {
        val wasAll = all
        all = true
        try { return run() } finally { all = wasAll }
    }

    private fun audience(): List<ServerPlayer> {
        val a = acting
        return if (a != null && !toAll) listOf(a) else players()
    }

    private var guarded = false

    fun guardBlocks() {
        if (guarded) return
        guarded = true
        PlayerBlockBreakEvents.BEFORE.register { level, _, _, _, _ -> !SimServer.isSimLevel(level) }
        UseBlockCallback.EVENT.register { _, level, _, _ ->
            if (SimServer.isSimLevel(level)) InteractionResult.FAIL else InteractionResult.PASS
        }
    }

    val me: String get() = player?.gameProfile?.name ?: "You"

    val hostName: String get() = host?.gameProfile?.name ?: "You"

    fun isPlayerName(name: String?) = name != null && players().any { it.gameProfile.name == name }

    fun chat(text: String) {
        val to = audience()
        if (to.any { isHost(it) }) Recorder.event(text)
        to.forEach { it.sendSystemMessage(Component.literal(text)) }
    }

    fun chatStyled(text: String) {
        val to = audience()
        if (to.any { isHost(it) }) Recorder.event(text)
        to.forEach { it.sendSystemMessage(legacy(text)) }
    }

    fun chatOthers(text: String) {
        val a = acting ?: return
        for (p in players()) if (p !== a) {
            if (isHost(p)) Recorder.event(text)
            p.sendSystemMessage(Component.literal(text))
        }
    }

    fun note(text: String) = chat("§8[§6ADS§8] §7$text")

    fun legacy(text: String): Component {
        val out = Component.empty()
        var style = Style.EMPTY
        val sb = StringBuilder()
        var i = 0
        fun flush() { if (sb.isNotEmpty()) { out.append(Component.literal(sb.toString()).withStyle(style)); sb.clear() } }
        while (i < text.length) {
            val c = text[i]
            if (c == '§' && i + 1 < text.length) {
                val f = ChatFormatting.getByCode(text[i + 1])
                if (f != null) {
                    flush()
                    style = if (f == ChatFormatting.RESET) Style.EMPTY
                        else if (TextColor.fromLegacyFormat(f) != null) Style.EMPTY.withColor(f) else style.applyFormat(f)
                    i += 2; continue
                }
            }
            sb.append(c); i++
        }
        flush()
        return out
    }

    fun boss(name: String, line: String, at: Vec3? = null, stand: Boolean = true) = everyone {
        chat("§4[BOSS] $name§r§c: $line")
        if (stand) BossWither.speak(name, line)
        sound(SoundEvents.WITHER_AMBIENT, 5f, 1.19f, at)
    }

    fun title(title: String, sub: String = "", fadeIn: Int = 0, stay: Int = 30, fadeOut: Int = 5) {
        val to = audience()
        if (to.any { isHost(it) }) Recorder.event("title: $title | $sub")
        for (p in to) {
            p.connection.send(ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut))
            p.connection.send(ClientboundSetTitleTextPacket(legacy(title)))
            p.connection.send(ClientboundSetSubtitleTextPacket(legacy(sub)))
        }
    }

    fun sound(sound: SoundEvent, volume: Float = 1f, pitch: Float = 1f, at: Vec3? = null, source: SoundSource = SoundSource.MASTER) {
        if (at != null) { level.playSound(null, at.x, at.y, at.z, sound, source, volume, pitch); return }
        val holder = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound)
        val seed = level.random.nextLong()
        for (p in audience()) p.connection.send(ClientboundSoundPacket(holder, source, p.x, p.y, p.z, volume, pitch, seed))
    }

    fun sound(sound: Holder<SoundEvent>, volume: Float = 1f, pitch: Float = 1f, at: Vec3? = null, source: SoundSource = SoundSource.MASTER) = sound(sound.value(), volume, pitch, at, source)

    fun <T : Entity> spawn(e: T): T {
        e.addTag(TAG)
        level.addFreshEntity(e)
        return e
    }

    fun clearEntities() {
        val l = level
        val all = ArrayList<Entity>()
        l.allEntities.forEach { if (it.entityTags().contains(TAG)) all += it }
        all.forEach { it.discard() }
    }

    fun tp(p: ServerPlayer, x: Double, y: Double, z: Double, yaw: Float? = null, pitch: Float? = null) {
        p.teleportTo(level, x, y, z, emptySet<Relative>(), yaw ?: p.yRot, pitch ?: p.xRot, false)
        p.deltaMovement = Vec3.ZERO
        p.fallDistance = 0.0
    }

    fun box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) = AABB(x0, y0, z0, x1, y1, z1)

    fun pos(x: Int, y: Int, z: Int) = BlockPos(x, y, z)

    fun command(cmd: String) {
        val s = SimServer.server ?: return
        s.commands.performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), cmd)
    }
}

class PerPlayer<T>(private val init: () -> T) : ReadWriteProperty<Any?, T> {
    private class Box<T>(@Volatile var v: T)
    private val map = ConcurrentHashMap<UUID, Box<T>>()
    init { all += this }
    private fun box(): Box<T> = map.getOrPut(Sim.player?.uuid ?: NOBODY) { Box(init()) }
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = box().v
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) { box().v = value }
    fun values(): List<T> = map.values.map { it.v }
    companion object {
        private val NOBODY = UUID(0, 0)
        private val all = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<PerPlayer<*>, Boolean>()))
        fun clearAll() = synchronized(all) { all.toList() }.forEach { it.map.clear() }
    }
}

fun <T> perPlayer(init: () -> T) = PerPlayer(init)
