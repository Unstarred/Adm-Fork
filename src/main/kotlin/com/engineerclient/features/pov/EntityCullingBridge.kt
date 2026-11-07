package com.engineerclient.features.pov

import net.fabricmc.loader.api.FabricLoader
import java.lang.reflect.Field

object EntityCullingBridge {

    private const val VERSIONLESS = "dev.tr7zw.entityculling.versionless.EntityCullingVersionlessBase"
    private const val MOD_BASE = "dev.tr7zw.entityculling.EntityCullingModBase"

    private val present: Boolean = FabricLoader.getInstance().isModLoaded("entityculling")

    private val enabledField by lazy {
        if (!present) null
        else runCatching { Class.forName(VERSIONLESS).getField("enabled") }.getOrNull()
    }

    private val modBaseClass by lazy {
        if (!present) null else runCatching { Class.forName(MOD_BASE) }.getOrNull()
    }

    fun disable(): Boolean? {
        val field = enabledField ?: return null
        return runCatching {
            val was = field.getBoolean(null)
            field.setBoolean(null, false)
            was
        }.getOrNull()
    }

    fun restore(previous: Boolean?) {
        val field = enabledField ?: return
        if (previous == null) return
        runCatching { field.setBoolean(null, previous) }
    }

    fun requestRecull() {
        val instanceField = instanceField ?: return
        val cullTaskField = cullTaskField ?: return
        runCatching {
            val instance = instanceField.get(null) ?: return
            val task = cullTaskField.get(instance) ?: return
            val requestCull = requestCullField?.takeIf { it.declaringClass.isInstance(task) }
                ?: task.javaClass.getField("requestCull").also { requestCullField = it }
            requestCull.setBoolean(task, true)
        }
    }

    private val instanceField by lazy { modBaseClass?.let { runCatching { it.getField("instance") }.getOrNull() } }
    private val cullTaskField by lazy { modBaseClass?.let { runCatching { it.getField("cullTask") }.getOrNull() } }
    private var requestCullField: Field? = null
}
