package com.engineerclient.pov

import com.engineerclient.EngineerClient
import com.engineerclient.OdinHuds
import com.engineerclient.mixin.CameraAccessor
import com.engineerclient.mixin.GameRendererInvoker
import com.engineerclient.rotation.EcLog
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.platform.Window
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.FilterMode
import com.odtheking.odin.features.ModuleManager
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.CameraType
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.state.gui.GuiRenderState
import net.minecraft.client.renderer.state.level.LevelRenderState
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Marker
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import org.joml.Matrix3x2f
import org.joml.Matrix4fStack
import org.joml.Vector4f
import java.lang.reflect.Field
import java.util.UUID

object PovCapture {

    private const val FEEDS = 4

    private const val COST_LINE_NANOS = 250_000_000L

    private val feeds = arrayOfNulls<RenderTarget>(FEEDS)

    private var feedHud3D: RenderTarget? = null

    private val empty = BooleanArray(FEEDS) { true }

    private val outOfRange = BooleanArray(FEEDS)

    private var feedWidth = 0
    private var feedHeight = 0

    var capturing = false
        private set

    private var nextFeed = 0
    private var freeRequested = false

    private var blitsSubmitted = 0
    private var skippedMainPass = false

    @Volatile
    var disabledForSession = false
        private set

    private var lastCostMs = 0.0

    private class Saved(
        val cameraEntity: Entity?,
        val windowWidth: Int,
        val windowHeight: Int,
        val cameraType: CameraType,
        val hudHidden: Boolean,
        val capturedFrustum: Frustum?,
        val eyeHeight: Float,
        val eyeHeightOld: Float,
        val target: RenderTarget,
        val hud3D: RenderTarget,
    )

    private class Carried(state: LevelRenderState) {
        private val sectionUpdates = ArrayList(state.sectionUpdateRenderStates)
        private val loading = state.chunkLoadingRenderState.let {
            listOf(it.addedEmptySections, it.removedEmptySections, it.addedLoadedChunks, it.removedLoadedChunks, it.loadedExpectedChunks).map(::LongOpenHashSet)
        }
        private val resetChunkLayerSampler = state.shouldResetChunkLayerSampler
        private val resetSkyRenderer = state.shouldResetSkyRenderer

        fun into(state: LevelRenderState) {
            state.sectionUpdateRenderStates.addAll(0, sectionUpdates)
            state.chunkLoadingRenderState.let {
                listOf(it.addedEmptySections, it.removedEmptySections, it.addedLoadedChunks, it.removedLoadedChunks, it.loadedExpectedChunks)
            }.forEachIndexed { i, set -> set.addAll(loading[i]) }
            state.shouldResetChunkLayerSampler = state.shouldResetChunkLayerSampler || resetChunkLayerSampler
            state.shouldResetSkyRenderer = state.shouldResetSkyRenderer || resetSkyRenderer
        }
    }

    private var carried: Carried? = null

    fun requestFree() {
        freeRequested = true
    }

    fun onWorldChange() {
        freeRequested = true
        standIn = null
        groundCache.clear()
        seenAs.clear()
    }

    fun releaseNow() {
        freeFeedsNow()
    }

    private var costText: String? = null
    private var costTextAt = 0L

    fun costLine(): String? {
        if (!PovPreviews.wants() || !PovPreviews.showCost) return null
        val now = System.nanoTime()
        costText?.let { if (now - costTextAt < COST_LINE_NANOS) return it }
        val live = (0 until FEEDS).count { !empty[it] && !outOfRange[it] }
        val line = "§7POV §f${"%.1f".format(lastCostMs)}§7ms  §f$live§7/4" +
            (if (SodiumBridge.available && PovPreviews.recullTerrain) " §8recull" else "")
        costText = line
        costTextAt = now
        return line
    }

    fun onScreenExtract(gfx: GuiGraphicsExtractor) {
        blitsSubmitted = 0
        if (disabledForSession) return
        val mc = EngineerClient.mc
        if (!PovPreviews.wants() || mc.level == null || mc.player == null) {
            freeFeedsNow()
            return
        }
        val main = mc.gameRenderer.mainRenderTarget()
        val scale = PovPreviews.resolution
        val width = ((main.width / 2) * scale).toInt()
        val height = ((main.height / 2) * scale).toInt()
        if (width < 16 || height < 16) return

        ensureFeeds(width, height)
        for (index in 0 until FEEDS) {
            val slot = DungeonUtils.leapTeammates.getOrNull(index)
            empty[index] = slot == null || slot.name == "Empty"
        }
        submitBlits(gfx)
        redrawKeptHuds(gfx)
    }

    private fun submitBlits(gfx: GuiGraphicsExtractor) {
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        val guiWidth = gfx.guiWidth()
        val guiHeight = gfx.guiHeight()
        val pose = Matrix3x2f(gfx.pose())
        val opacity = PovPreviews.opacity
        val opaque = opacity >= 1f
        val pipeline = if (opaque) RenderPipelines.GUI_OPAQUE_TEXTURED_BACKGROUND else RenderPipelines.GUI_TEXTURED
        val tint = if (opaque) -1 else ((opacity * 255f).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
        for (index in 0 until FEEDS) {
            if (empty[index]) continue
            val view = feeds[index]?.colorTextureView ?: continue
            val column = index % 2
            val row = index / 2
            val x0 = if (column == 0) 0 else guiWidth / 2
            val x1 = if (column == 0) guiWidth / 2 else guiWidth
            val y0 = if (row == 0) 0 else guiHeight / 2
            val y1 = if (row == 0) guiHeight / 2 else guiHeight
            gfx.guiRenderState.addGuiElement(
                BlitRenderState(
                    pipeline,
                    TextureSetup.singleTexture(view, sampler),
                    pose,
                    x0, y0, x1, y1,
                    0f, 1f, 1f, 0f,
                    tint,
                    null,
                )
            )
            blitsSubmitted++
        }
    }

    private fun redrawKeptHuds(gfx: GuiGraphicsExtractor) {
        for (hud in ModuleManager.hudSettingsCache) {
            if (!hud.isEnabled || !PovPreviews.keepsHud(hud.name)) continue
            try {
                OdinHuds.redraw(gfx, hud)
            } catch (t: Throwable) {
                EngineerClient.logger.error("[ec] pov keep hud ${hud.name} failed", t)
            }
        }
    }

    fun beforeLevelRender(deltaTracker: DeltaTracker): Boolean {
        if (capturing || disabledForSession) return false
        skippedMainPass = false
        if (!PovPreviews.skipOwnView || PovPreviews.opacity < 1f || !canCapture()) return false
        if (blitsSubmitted < FEEDS) return false
        carried = Carried(EngineerClient.mc.gameRenderer.gameRenderState().levelRenderState)
        val ran = runPasses(deltaTracker)
        if (carried != null) {
            carried = null
            return false
        }
        if (ran == 0) return false

        val main = EngineerClient.mc.gameRenderer.mainRenderTarget()
        val color = main.colorTexture
        val depth = main.depthTexture
        if (color != null && depth != null) {
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(color, Vector4f(0f, 0f, 0f, 1f), depth, 0.0)
        }
        blitsSubmitted = 0
        skippedMainPass = true
        return true
    }

    fun afterLevelRender(deltaTracker: DeltaTracker) {
        if (capturing || disabledForSession) return
        if (skippedMainPass) {
            skippedMainPass = false
            return
        }
        if (blitsSubmitted == 0 || !canCapture()) return
        blitsSubmitted = 0
        runPasses(deltaTracker)
    }

    private fun canCapture(): Boolean {
        val mc = EngineerClient.mc
        return PovPreviews.wants() && mc.level != null && mc.player != null && feeds[0] != null
    }

    private fun runPasses(deltaTracker: DeltaTracker): Int {
        val started = System.nanoTime()
        var ran = 0
        capturing = true
        try {
            repeat(PovPreviews.previewsPerFrame.coerceIn(1, FEEDS)) {
                renderFeed(nextFeed, deltaTracker)
                nextFeed = (nextFeed + 1) % FEEDS
                ran++
            }
            restoreMainCamera(deltaTracker)
            EntityCullingBridge.requestRecull()
        } catch (t: Throwable) {
            fail(t)
            EngineerClient.safely("pov camera restore") { restoreMainCamera(deltaTracker) }
        } finally {
            capturing = false
        }
        lastCostMs = (System.nanoTime() - started) / 1_000_000.0
        return ran
    }

    private val OUT_OF_RANGE = Vector4f(0x14 / 255f, 0x14 / 255f, 0x14 / 255f, 1f)

    private fun renderFeed(index: Int, deltaTracker: DeltaTracker) {
        val mc = EngineerClient.mc
        if (empty[index]) return
        val feed = feeds[index] ?: return
        val slot = DungeonUtils.leapTeammates.getOrNull(index) ?: return

        val loaded: Player? = (slot.entity?.takeIf { !it.isRemoved } ?: findByName(slot.name))?.takeIf { !it.isRemoved }
        if (loaded != null) seenAs[slot.name] = loaded.uuid
        val target: Entity? = when {
            loaded == null -> recentStandIn(slot) ?: mapStandIn(slot)
            loaded === mc.player || !loaded.isAlive -> null
            else -> loaded
        }
        if (target == null) {
            if (!outOfRange[index]) {
                outOfRange[index] = true
                feed.colorTexture?.let { RenderSystem.getDevice().createCommandEncoder().clearColorTexture(it, OUT_OF_RANGE) }
            }
            return
        }
        outOfRange[index] = false

        renderPass(mc, feed, target, deltaTracker)
    }

    private fun renderPass(mc: Minecraft, feed: RenderTarget, target: Entity, deltaTracker: DeltaTracker) {
        val gameRenderer = mc.gameRenderer
        val invoker = gameRenderer as GameRendererInvoker
        val camera = gameRenderer.mainCamera()
        val cameraAccess = camera as CameraAccessor
        val window = mc.window
        val options = mc.options
        val gui = gameRenderer.gameRenderState().guiRenderState

        val saved = saveState(mc, gameRenderer, invoker, cameraAccess, window, options, gui)
        var pose: PovPose.Restore? = null
        var cullingWas: Boolean? = null
        val modelViewDepth = modelViewDepth()

        try {
            val worldPartialTicks = deltaTracker.getGameTimeDeltaPartialTick(false)
            if (target is Player) pose = PovPose.begin(target, PovPreviews.mode, PovPreviews.smoothingTicks, worldPartialTicks)
            val eyeHeight = if (target is Player) target.eyeHeight else STANDING_EYE

            mc.setCameraEntity(target)
            options.cameraType = CameraType.FIRST_PERSON
            gui.isHudHidden = true
            window.setWidth(feed.width)
            window.setHeight(feed.height)
            cameraAccess.`ec$setEyeHeight`(eyeHeight)
            cameraAccess.`ec$setEyeHeightOld`(eyeHeight)
            invoker.`ec$setMainRenderTarget`(feed)
            invoker.`ec$setHud3DTarget`(feedHud3D ?: saved.hud3D)

            camera.update(deltaTracker)
            if (!(PovPreviews.recullTerrain && SodiumBridge.available)) cameraAccess.`ec$setCapturedFrustum`(camera.cullFrustum)
            cullingWas = EntityCullingBridge.disable()

            invoker.`ec$extractWindow`()
            invoker.`ec$extractOptions`()
            invoker.`ec$extractCamera`(deltaTracker, worldPartialTicks)
            mc.levelExtractor.extract(deltaTracker, camera, worldPartialTicks)
            carried?.into(gameRenderer.gameRenderState().levelRenderState)
            carried = null

            gameRenderer.renderLevel()
        } finally {
            unwindVanillaState(modelViewDepth)
            invoker.`ec$setMainRenderTarget`(saved.target)
            invoker.`ec$setHud3DTarget`(saved.hud3D)
            window.setWidth(saved.windowWidth)
            window.setHeight(saved.windowHeight)
            options.cameraType = saved.cameraType
            gui.isHudHidden = saved.hudHidden
            cameraAccess.`ec$setCapturedFrustum`(saved.capturedFrustum)
            cameraAccess.`ec$setEyeHeight`(saved.eyeHeight)
            cameraAccess.`ec$setEyeHeightOld`(saved.eyeHeightOld)
            mc.setCameraEntity(saved.cameraEntity)
            pose?.restore()
            EntityCullingBridge.restore(cullingWas)
        }
    }

    private fun saveState(
        mc: Minecraft,
        gameRenderer: GameRenderer,
        invoker: GameRendererInvoker,
        cameraAccess: CameraAccessor,
        window: Window,
        options: Options,
        gui: GuiRenderState,
    ): Saved {
        val saved = Saved(
            cameraEntity = mc.cameraEntity,
            windowWidth = window.width,
            windowHeight = window.height,
            cameraType = options.cameraType,
            hudHidden = gui.isHudHidden,
            capturedFrustum = cameraAccess.`ec$getCapturedFrustum`(),
            eyeHeight = cameraAccess.`ec$getEyeHeight`(),
            eyeHeightOld = cameraAccess.`ec$getEyeHeightOld`(),
            target = gameRenderer.mainRenderTarget(),
            hud3D = invoker.`ec$getHud3DTarget`(),
        )
        return saved
    }

    private fun restoreMainCamera(deltaTracker: DeltaTracker) {
        val mc = EngineerClient.mc
        if (mc.level == null || mc.player == null) return
        val gameRenderer = mc.gameRenderer
        val camera = gameRenderer.mainCamera()
        val invoker = gameRenderer as GameRendererInvoker
        camera.update(deltaTracker)
        val worldPartialTicks = deltaTracker.getGameTimeDeltaPartialTick(false)
        invoker.`ec$extractWindow`()
        invoker.`ec$extractOptions`()
        invoker.`ec$extractCamera`(deltaTracker, worldPartialTicks)
    }

    private fun ensureFeeds(width: Int, height: Int) {
        if (!freeRequested && feedWidth == width && feedHeight == height && feeds.all { it != null }) return
        freeFeedsNow()
        feedWidth = width
        feedHeight = height
        for (index in 0 until FEEDS) {
            val feed = TextureTarget("ec:pov$index", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT)
            feed.colorTexture?.let { RenderSystem.getDevice().createCommandEncoder().clearColorTexture(it, OUT_OF_RANGE) }
            feeds[index] = feed
        }
        feedHud3D = TextureTarget("ec:pov_hud_3d_depth", width, height, null, GpuFormat.D32_FLOAT)
        outOfRange.fill(false)
        nextFeed = 0
    }

    private fun freeFeedsNow() {
        freeRequested = false
        if (feeds.all { it == null }) return
        for (index in 0 until FEEDS) {
            feeds[index]?.destroyBuffers()
            feeds[index] = null
        }
        feedHud3D?.destroyBuffers()
        feedHud3D = null
        feedWidth = 0
        feedHeight = 0
        nextFeed = 0
    }

    private const val STANDING_EYE = 1.62f

    private var standIn: Marker? = null

    private val seenAs = HashMap<String, UUID>()

    private const val RANGE_GRACE_NANOS = 2_000_000_000L

    private fun recentStandIn(p: DungeonPlayer): Entity? {
        if (p.isDead) return null
        val s = PovPose.lastSeen(seenAs[p.name] ?: return null) ?: return null
        if (System.nanoTime() - s.nanos > RANGE_GRACE_NANOS) return null
        return standIn(s.x, s.y, s.z, s.headYaw, s.pitch)
    }

    private fun standIn(x: Double, y: Double, z: Double, yaw: Float, pitch: Float): Entity? {
        val level = EngineerClient.mc.level ?: return null
        val e = standIn?.takeIf { it.level() === level } ?: Marker(EntityTypes.MARKER, level).also { standIn = it }
        e.setPos(x, y, z)
        e.xo = x; e.yo = y; e.zo = z
        e.setYRot(yaw); e.yRotO = yaw
        e.setXRot(pitch); e.xRotO = pitch
        return e
    }

    private val groundCache = HashMap<String, Triple<Int, Int, Int>>()

    private fun mapStandIn(p: DungeonPlayer): Entity? {
        if (!DungeonUtils.inDungeons || DungeonUtils.inBoss || p.isDead) return null
        if (p.mapPos.x == 0 && p.mapPos.z == 0) return null
        val level = EngineerClient.mc.level ?: return null
        val x = ((p.mapPos.x + 128) / 2.0 - DungeonScan.startX) * 32.0 / DungeonScan.roomGap - 200
        val z = ((p.mapPos.z + 128) / 2.0 - DungeonScan.startY) * 32.0 / DungeonScan.roomGap - 200
        val bx = Math.floor(x).toInt()
        val bz = Math.floor(z).toInt()
        val cached = groundCache[p.name]
        val y = if (cached != null && cached.first == bx && cached.second == bz) cached.third
            else groundY(level, bx, bz).also { groundCache[p.name] = Triple(bx, bz, it) }

        return standIn(x, y.toDouble(), z, p.yaw, 0f)
    }

    private fun groundY(level: Level, x: Int, z: Int): Int {
        if (!level.isLoaded(BlockPos(x, 69, z))) return 69
        fun blocks(y: Int): Boolean {
            val pos = BlockPos(x, y, z)
            val s = level.getBlockState(pos)
            return !s.isAir && s.isCollisionShapeFullBlock(level, pos)
        }
        fun standOn(y: Int): Boolean {
            val s = level.getBlockState(BlockPos(x, y, z))
            return !s.isAir && s.fluidState.isEmpty
        }
        for (d in 0..40) for (y in if (d == 0) intArrayOf(69) else intArrayOf(69 + d, 69 - d)) {
            if (standOn(y - 1) && !blocks(y) && !blocks(y + 1)) return y
        }
        return 69
    }

    private fun findByName(name: String): Player? =
        EngineerClient.mc.level?.players()?.firstOrNull { it.name.string.equals(name, ignoreCase = true) }

    private fun unwindVanillaState(modelViewDepth: Int) {
        EngineerClient.safely("pov model-view unwind") { popModelViewTo(modelViewDepth) }
        EngineerClient.safely("pov frame release") { releasePreparedFrame() }
    }

    private val CURR_FIELD: Field? by lazy {
        try {
            Matrix4fStack::class.java.getDeclaredField("curr").apply { isAccessible = true }
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ec] pov: can't read the model-view stack depth, a failed pass won't be unwound", t)
            null
        }
    }

    private fun modelViewDepth(): Int = try {
        CURR_FIELD?.getInt(RenderSystem.getModelViewStack()) ?: -1
    } catch (t: Throwable) {
        -1
    }

    private fun popModelViewTo(depth: Int) {
        if (depth < 0) return
        val stack = RenderSystem.getModelViewStack()
        var current = modelViewDepth()
        if (current <= depth) return
        while (current > depth) {
            stack.popMatrix()
            current--
        }
        EngineerClient.logger.info("[ec] pov: popped the model-view stack a failed pass left pushed")
    }

    private fun releasePreparedFrame() {
        val dispatcher = EngineerClient.mc.gameRenderer.featureRenderDispatcher()
        val frame = FRAME_FIELD.get(dispatcher) as FeatureRenderDispatcher.PreparedFrame
        if (CONTEXT_FIELD.get(frame) == null) return
        try {
            frame.close()
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ec] pov: closing the left-over frame failed, marking it free", t)
            CONTEXT_FIELD.set(frame, null)
            STORAGE_FIELD.set(frame, null)
        }
        EngineerClient.logger.info("[ec] pov: freed the frame a failed pass left in use")
    }

    private val FRAME_FIELD by lazy { FeatureRenderDispatcher::class.java.getDeclaredField("preparedFrame").apply { isAccessible = true } }
    private val CONTEXT_FIELD by lazy { FeatureRenderDispatcher.PreparedFrame::class.java.getDeclaredField("context").apply { isAccessible = true } }
    private val STORAGE_FIELD by lazy { FeatureRenderDispatcher.PreparedFrame::class.java.getDeclaredField("submitNodeStorage").apply { isAccessible = true } }

    private fun fail(t: Throwable) {
        disabledForSession = true
        freeRequested = true
        EngineerClient.logger.error("[ec] pov preview failed — disabled for this session", t)
        EcLog.log("WARN", "pov preview failed: ${t.javaClass.simpleName}: ${t.message}")
        EngineerClient.msg("§cPOV previews hit an error and are off for this session §7(see the log).")
    }
}
