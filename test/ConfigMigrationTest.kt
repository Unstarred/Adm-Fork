package com.engineerclient

import com.google.gson.JsonParser
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigMigrationTest {

    @TempDir
    lateinit var game: Path

    private val addons get() = game.resolve("config/odin/addons")

    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    @Test
    fun `engineer client's settings file is copied to adm's once`() {
        write(addons.resolve("engineerclient.json"), "[]")
        ConfigMigration.rename(game)
        assertEquals("[]", Files.readString(addons.resolve("adm.json")))
        assertTrue(Files.exists(addons.resolve("engineerclient.json")))
        assertFalse(Files.exists(addons.resolve("adm.json.tmp")))
    }

    @Test
    fun `an existing adm settings file is never overwritten`() {
        write(addons.resolve("engineerclient.json"), "[\"old\"]")
        write(addons.resolve("adm.json"), "[\"mine\"]")
        ConfigMigration.rename(game)
        assertEquals("[\"mine\"]", Files.readString(addons.resolve("adm.json")))
    }

    @Test
    fun `engineer client's folder moves to adm's, unless adm's exists`() {
        write(game.resolve("config/engineerclient/runs/a.txt"), "run")
        ConfigMigration.rename(game)
        assertEquals("run", Files.readString(game.resolve("config/adm/runs/a.txt")))
        assertFalse(Files.exists(game.resolve("config/engineerclient")))

        write(game.resolve("config/engineerclient/runs/b.txt"), "other")
        ConfigMigration.rename(game)
        assertTrue(Files.exists(game.resolve("config/engineerclient/runs/b.txt")))
        assertFalse(Files.exists(game.resolve("config/adm/runs/b.txt")))
    }

    @Test
    fun `no settings file is nothing to migrate`() {
        assertFalse(ConfigMigration.run(game.resolve("config/odin")))
        assertFalse(Files.exists(addons.resolve("adm.json")))
    }

    @Test
    fun `renamed modules and settings keep their values, once`() {
        write(addons.resolve("adm.json"), """[
            {"name":"BR Waypoints 2","enabled":true,"settings":{}},
            {"name":"ADR","enabled":true,"settings":{"Private Runs":true}},
            {"name":"ADS","enabled":true,"settings":{"Shortbow Cooldown":2}}
        ]""")
        val odin = game.resolve("config/odin")
        assertTrue(ConfigMigration.run(odin))
        val modules = JsonParser.parseString(Files.readString(addons.resolve("adm.json"))).asJsonArray.map { it.asJsonObject }
        val byName = modules.associateBy { it["name"].asString }
        assertTrue("BR Roles" in byName)
        assertFalse("BR Waypoints 2" in byName)
        val adr = byName.getValue("ADR").getAsJsonObject("settings")
        assertTrue(adr["Unlisted Runs"].asBoolean)
        assertTrue(adr["Unlisted Runs Checked"].asBoolean)
        assertFalse(adr.has("Private Runs"))
        val ads = byName.getValue("ADS").getAsJsonObject("settings")
        assertFalse(ads.has("Shortbow Cooldown"))
        assertFalse(ads.has("Shortbow Shot Cooldown"))
        assertFalse(ConfigMigration.run(odin))
    }

    @Test
    fun `a shortbow cooldown that isn't the wrong default is kept`() {
        write(addons.resolve("adm.json"), """[{"name":"ADS","enabled":true,"settings":{"Shortbow Cooldown":7}}]""")
        assertTrue(ConfigMigration.run(game.resolve("config/odin")))
        val ads = JsonParser.parseString(Files.readString(addons.resolve("adm.json"))).asJsonArray
            .first { it.asJsonObject["name"].asString == "ADS" }.asJsonObject.getAsJsonObject("settings")
        assertEquals(7, ads["Shortbow Shot Cooldown"].asInt)
    }
}
