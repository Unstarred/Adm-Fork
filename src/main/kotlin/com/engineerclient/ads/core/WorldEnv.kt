package com.engineerclient.ads.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

object WorldEnv {
    const val PACK_NAME = "ads-env"

    const val PACK_ID = "file/$PACK_NAME"

    const val MIN_Y = 0
    const val HEIGHT = 256

    private const val AMBIENT_SOUNDS_KEY = "minecraft:audio/ambient_sounds"

    fun dimensionTypeJson(vanilla: String): String {
        val o = JsonParser.parseString(vanilla).asJsonObject
        o.addProperty("has_skylight", false)
        o.addProperty("has_ceiling", false)
        o.addProperty("min_y", MIN_Y)
        o.addProperty("height", HEIGHT)
        o.addProperty("logical_height", HEIGHT)
        o.addProperty("ambient_light", 0.0)
        (o.get("attributes") as? JsonObject)?.remove(AMBIENT_SOUNDS_KEY)
        return GSON.toJson(o)
    }

    fun packMetaJson(major: Int, minor: Int): String {
        val pack = JsonObject()
        pack.addProperty("description", "ADS world settings")
        pack.add("min_format", GSON.toJsonTree(intArrayOf(major, minor)))
        pack.addProperty("max_format", major)
        val root = JsonObject()
        root.add("pack", pack)
        return GSON.toJson(root)
    }

    fun installPack(worldDir: Path, vanillaDimensionJson: String, packMajor: Int, packMinor: Int): Path {
        val root = worldDir.resolve("datapacks").resolve(PACK_NAME)
        val dimension = dimensionTypeJson(vanillaDimensionJson)
        val meta = packMetaJson(packMajor, packMinor)
        val dimFile = root.resolve("data").resolve("minecraft").resolve("dimension_type").resolve("overworld.json")
        Files.createDirectories(dimFile.parent)
        Files.writeString(root.resolve("pack.mcmeta"), meta)
        Files.writeString(dimFile, dimension)
        return root
    }

    fun chunksOf(minX: Int, minZ: Int, width: Int, depth: Int): List<Pair<Int, Int>> {
        if (width <= 0 || depth <= 0) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        for (cx in (minX shr 4)..((minX + width - 1) shr 4)) for (cz in (minZ shr 4)..((minZ + depth - 1) shr 4)) out += cx to cz
        return out
    }

    private val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
}
