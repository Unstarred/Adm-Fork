package com.engineerclient.core

import com.google.gson.GsonBuilder
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import com.engineerclient.EngineerClient

object EcConfig {

    data class Data(
        var lastKnownClass: String? = null,
        var myStartingRole: String? = null,
        var roleBindings: MutableMap<String, String>? = null,
    )

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("adm").resolve("config.json")

    var data: Data = Data()
        private set

    fun load(): Boolean {
        try {
            if (Files.exists(file)) {
                data = gson.fromJson(Files.readString(file), Data::class.java) ?: Data()
                migrateRoleBindings()
                return false
            }
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ec] failed to load config, keeping defaults", t)
            return false
        }
        save()
        return true
    }

    fun migrateRoleBindings() {
        val old = data.roleBindings ?: return
        val me = Minecraft.getInstance().user?.name ?: return
        if (data.myStartingRole == null) data.myStartingRole = old.entries.firstOrNull { it.value.equals(me, true) }?.key
        data.roleBindings = null
        save()
    }

    fun save() {
        try {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("config.json.tmp")
            Files.writeString(tmp, gson.toJson(data))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ec] failed to save config", t)
        }
    }
}
