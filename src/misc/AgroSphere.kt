package com.engineerclient.misc

import com.mojang.blaze3d.vertex.PoseStack
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3

object AgroSphere : Module(
    name = "Agro Sphere",
    category = Category.custom("ADM", 860, 10),
    description = "F7 only: in P1/P2, a see-through sphere around Maxor/Storm through the closest player: the aggro boundary. If you're closest, it shows your margin to the 2nd closest.",
    key = null,
) {
    private val sphereColor by ColorSetting("Sphere Color", Color(255, 85, 85, 0.18f), true, desc = "Sphere colour when someone else has aggro.")
    private val aggroColor by ColorSetting("Sphere Color (Your Aggro)", Color(85, 255, 85, 0.18f), true, desc = "Sphere colour when you are the closest and it shows the 2nd closest.")

    private const val LAT_BANDS = 24
    private const val LON_BANDS = 48

    private var boss: WitherBoss? = null
    private var players: List<Player> = emptyList()

    private var activePhase: M7Phases? = null
    private var sawBossLine = false
    private var leftPhase = false
    private var stormLightning = false
    private val stormLightningRegex = Regex("^\\[BOSS] Storm: (?:ENERGY HEED MY CALL!|THUNDER LET ME BE YOUR CATALYST!)$")
    private val maxorRegex = Regex("^\\[BOSS] Maxor: ")
    private val stormStartRegex = Regex("^\\[BOSS] Storm: Pathetic Maxor, just like expected\\.$")
    private val stormEndRegex = Regex("^\\[BOSS] Storm: I should have known that I stood no chance\\.$")
    private val goldorStartRegex = Regex("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$")

    data class Member(val isMe: Boolean, val distance: Double)
    data class Result(val radius: Double, val youHaveAggro: Boolean)

    fun radius(distances: List<Member>): Result? {
        val sorted = distances.sortedBy { it.distance }
        val closest = sorted.firstOrNull() ?: return null
        if (!closest.isMe) return Result(closest.distance, youHaveAggro = false)
        val second = sorted.getOrNull(1) ?: return null
        return Result(second.distance, youHaveAggro = true)
    }

    init {
        on<LevelEvent.Load> { activePhase = null; sawBossLine = false; leftPhase = false; stormLightning = false }

        on<MessageEvent.Chat> {
            when {
                stormStartRegex.matches(message) -> { activePhase = M7Phases.P2; sawBossLine = true; leftPhase = false; stormLightning = false }
                stormLightningRegex.matches(message) -> stormLightning = true
                stormEndRegex.matches(message) || goldorStartRegex.matches(message) -> { activePhase = null; sawBossLine = true }
                maxorRegex.containsMatchIn(message) && !sawBossLine -> { activePhase = M7Phases.P1; sawBossLine = true; leftPhase = false }
            }
        }

        on<TickEvent.End> {
            if (DungeonUtils.floor?.name != "F7") { boss = null; players = emptyList(); return@on }
            val here = DungeonUtils.getF7Phase()
            if (sawBossLine && activePhase != null && here != activePhase) leftPhase = true
            val phase = (if (leftPhase) null else if (sawBossLine) activePhase else here)
                ?.takeUnless { it == M7Phases.P2 && sawBossLine && !stormLightning }
            val name = when (phase) {
                M7Phases.P1 -> "Maxor"
                M7Phases.P2 -> "Storm"
                else -> null
            }
            if (name == null) { boss = null; players = emptyList(); return@on }
            val me = mc.player ?: return@on

            val withers = level.entitiesForRendering().filterIsInstance<WitherBoss>().filter { it.isAlive && Witherborn.isBoss(it) }
            boss = withers.filter { it.name.string.contains(name, true) }.minByOrNull { it.distanceToSqr(me) }
                ?: withers.minByOrNull { it.distanceToSqr(me) }
            if (boss == null) { players = emptyList(); return@on }

            players = DungeonUtils.dungeonTeammates.mapNotNull { teammate ->
                if (teammate.isDead) return@mapNotNull null
                teammate.entity?.takeIf { it.isAlive } ?: level.players().firstOrNull { it.name.string == teammate.name }
            }
        }

        on<RenderExtractEvent> {
            val boss = boss?.takeIf { it.isAlive } ?: return@on
            val me = mc.player ?: return@on
            val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(false)
            val center = boss.getPosition(pt)
            val members = players.filter { it.isAlive }.map { Member(it === me, it.getPosition(pt).distanceTo(center)) }
            val result = radius(members) ?: return@on
            val color = if (result.youHaveAggro) aggroColor else sphereColor
            drawSphere(context.poseStack(), context.submitNodeCollector(), center, result.radius, color)
        }
    }

    private fun drawSphere(
        pose: PoseStack,
        collector: SubmitNodeCollector,
        center: Vec3,
        radius: Double,
        color: Color,
    ) {
        if (radius <= 0.05) return
        val cam = mc.gameRenderer.mainCamera().position()
        pose.pushPose()
        pose.translate(center.x - cam.x, center.y - cam.y, center.z - cam.z)
        val argb = color.rgba
        val r = radius.toFloat()
        val u = unitSphere
        collector.submitCustomGeometry(pose, RenderTypes.debugQuads()) { last, buffer ->
            fun v(lat: Int, lon: Int) { val i = (lat * (LON_BANDS + 1) + lon) * 3; buffer.addVertex(last, u[i] * r, u[i + 1] * r, u[i + 2] * r).setColor(argb) }
            for (lat in 0 until LAT_BANDS) for (lon in 0 until LON_BANDS) {
                v(lat, lon); v(lat + 1, lon); v(lat + 1, lon + 1); v(lat, lon + 1)
            }
        }
        pose.popPose()
    }

    private val unitSphere = FloatArray((LAT_BANDS + 1) * (LON_BANDS + 1) * 3).also { a ->
        for (lat in 0..LAT_BANDS) for (lon in 0..LON_BANDS) {
            val theta = Math.PI * lat / LAT_BANDS
            val phi = 2 * Math.PI * lon / LON_BANDS
            val i = (lat * (LON_BANDS + 1) + lon) * 3
            a[i] = (Math.sin(theta) * Math.cos(phi)).toFloat(); a[i + 1] = Math.cos(theta).toFloat(); a[i + 2] = (Math.sin(theta) * Math.sin(phi)).toFloat()
        }
    }
}
