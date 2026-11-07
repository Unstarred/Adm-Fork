package com.engineerclient.ads.items

object Lore {
    private val GLYPH = Regex("\\{U([0-9A-F]{4})\\}")
    fun u(t: String) = GLYPH.replace(t) { it.groupValues[1].toInt(16).toChar().toString() }
    private fun lines(raw: String) = raw.trim('\n').split('\n').map { u(it) }
    val HIDDEN = listOf("minecraft:jukebox_playable", "minecraft:painting/variant", "minecraft:map_id", "minecraft:fireworks", "minecraft:attribute_modifiers", "minecraft:unbreakable",
        "minecraft:written_book_content", "minecraft:banner_patterns", "minecraft:trim", "minecraft:potion_contents", "minecraft:dyed_color", "minecraft:charged_projectiles")
    val HIDDEN_BREAKER = listOf("minecraft:painting/variant", "minecraft:fireworks", "minecraft:attribute_modifiers", "minecraft:enchantments", "minecraft:stored_enchantments", "minecraft:trim",
        "minecraft:charged_projectiles", "minecraft:jukebox_playable", "minecraft:map_id", "minecraft:unbreakable", "minecraft:written_book_content", "minecraft:banner_patterns", "minecraft:potion_contents", "minecraft:dyed_color")
    val SUPERBOOM_TNT_NAME = u("§9Superboom TNT")
    val SUPERBOOM_TNT = lines("""
§7Blows up cracked brick walls and
§7crypts, which are typically found in
§cDungeons §7and the §5Crystal Hollows§7.

§9§lRARE
""")
    val HYPERION_NAME = u("§dHeroic Hyperion §6✪✪✪✪✪")
    val HYPERION = lines("""
§7Gear Score: §d5000
§7Damage: §c+2,195.2 §e(+30)
§7Strength: §c+1,472 §e(+30) §9(+50)
§7Crit Damage: §9+448%
§7Attack Speed: §e+10.85% §9(+7%)
§7Ferocity: §c+46.5
§7Intelligence: §b+4,064 §9(+125) §d(+60)
§7Gemstones: §6[§b{UE003}§6] §6[§b⚔§6]

§d§lSwarm V, §9Bane of Arthropods VI, §9Champion X
§9Cleave V, §9Critical VI, §9Cubism V
§9Ender Slayer VI, §9Experience IV, §9Fire Aspect III
§9First Strike IV, §9Giant Killer VII, §9Impaling III
§9Lethality VI, §9Life Steal V, §9Looting IV
§9Luck VI, §9Prosecute VI, §9Scavenger V
§9Smite VII, §9Tabasco III, §9Thunderlord VI
§9Vampirism VI, §9Venomous V

§7Deals §c+50% §7damage to §8{UE085} Wither §7mobs.
§7Grants §c+1 §c{UE050} Damage §7and §a+2 §b{UE003}
§bIntelligence §7per §cCatacombs §7level.

§aScroll Abilities:
§b§l⦾ §6Ability: Wither Impact  §e§lRIGHT CLICK
§7Teleport §a10 blocks§7 ahead of you
§7dealing §c1,798,815.7 §7damage to nearby
§7enemies. Also reduces your damage
§7taken and grants an absorption
§7shield for §e5 seconds§7.
§8Mana Cost: §b270{UE003}

§fKills: §61,304,218

§d§la §d§lMYTHIC DUNGEON SWORD §d§la
""")
    val TERMINATOR_NAME = u("§dPrecise Terminator §6✪✪✪✪✪§c➎")
    val TERMINATOR = lines("""
§7Gear Score: §d4947
§7Damage: §c+2,195.2 §e(+30)
§7Strength: §c+761.6 §e(+30) §6[+5] §9(+34)
§7Crit Chance: §9+46.5% §9(+15%)
§7Crit Damage: §9+2,080% §9(+70%)
§7Attack Speed: §e+62%
§7Shot Cooldown: §a0.5s

§d§lSoul Eater V, §9Chance V, §9Cubism V
§9Flame II, §9Gravity VI, §9Impaling V
§9Infinite Quiver X, §9Overload V, §9Piercing I
§9Power VII, §9Snipe IV, §9Tabasco III
§9Toxophilite X

§7Shoots §b3 §7arrows at once.
§7Can damage endermen.

§cDivides your §9{UE02C} Crit Chance §cby 4!

§6Ability: Salvation  §e§lLEFT CLICK
§7Can be cast after landing §63 §7hits.
§7Shoot a beam, penetrating up to §e5
§7enemies.
§7The beam always crits.
§8Soulflow Cost: §31⸎

§dShortbow: Instantly shoots!

§fKills: §6157,045

§9Precise Bonus
§7Deal §a+10% §7extra damage when
§7arrows hit the head of a mob.

§8§l* §8Co-op Soulbound §8§l*
§d§la §d§lMYTHIC DUNGEON BOW §d§la
""")
    val WITHER_CLOAK_NAME = u("§6Odd Wither Cloak Sword")
    val WITHER_CLOAK = lines("""
§7Gear Score: §d4964
§7Defense: §a+1,475
§7Damage: §c+1,121
§7Strength: §c+796.5
§7Crit Chance: §9+26.25% §9(+25%)
§7Crit Damage: §9+177% §9(+30%)
§7Intelligence: §b-295 §9(-50)

§c§l⦾ §6Ability: Creeper Veil  §e§lRIGHT CLICK
§7Spawns a layered veil that negates
§7damage for §a10s§7, as follows:

§8➤ §7Lose a layer on hit, consuming
§430§4{UE028} Vitality §7and block up to §c5,850
§7damage.
§8➤ §7The damage blocked scales with
§7your §5Dungeon Stat Boost §7while in §cThe
§cCatacombs§7.
§8➤ §7When the damage exceeds a
§7layer's limit, another layer is
§7consumed, until all damage is
§7accounted for.
§8➤ §7When you run out of §4{UE028} Vitality§7,
§7consume all §4{UE028} Vitality §7and take slight
§7knockback.
§8➤ §7You cannot attack or regenerate
§4{UE028} Vitality §7while the veil is up.

§e§lRIGHT CLICK §7while active to
§7deactivate. Cooldown is halved on
§7deactivation.
§8Vitality Cost: §430{UE028}
§8Cooldown: §a10s

§cThis item can be reclaimed! Use
§e/reclaim §cto take back what's
§crightfully yours!
§6§la §6§lLEGENDARY DUNGEON SWORD §6§la
""")
    val ASPECT_OF_THE_VOID_NAME = u("§6Heroic Aspect of the Void")
    val ASPECT_OF_THE_VOID = lines("""
§7Damage: §c+120
§7Strength: §c+140 §9(+40)
§7Attack Speed: §e+5% §9(+5%)
§7Intelligence: §b+100 §9(+100)
§7Gemstones: §8[§7{UE003}§8]

§d§lUltimate Wise V
§7Reduces the ability mana cost of this
§7item by §a50%§7.

§b§l⦾ §6Ability: Instant Transmission  §e§lRIGHT CLICK
§7Teleport §a12 blocks§7 ahead of you and
§7gain §a+50 §f{UE022} Speed§7 for §a3 seconds§7.
§8Mana Cost: §b18{UE003}

§6Ability: Ether Transmission  §e§lSNEAK RIGHT CLICK
§7Teleport to your targeted block up
§7to §a61 blocks §7away.
§8Soulflow Cost: §31
§8Mana Cost: §b73{UE003}

§6§la §6§lLEGENDARY SWORD §6§la
""")
    val JERRY_STAFF_NAME = u("§6Heroic Jerry-chine Gun §6✪✪✪✪✪")
    val JERRY_STAFF = lines("""
§7Damage: §c+88
§7Strength: §c+40 §9(+40)
§7Attack Speed: §e+5% §9(+5%)
§7Intelligence: §b+320 §9(+100)

§d§lUltimate Wise V
§7Reduces the ability mana cost of this
§7item by §a50%§7.

§c§l⦾ §6Ability: Rapid-fire  §e§lRIGHT CLICK
§7Shoots a Jerry bullet, dealing
§c1,347.2 §7damage on impact and
§7knocking you back.

§7Each shot costs §3+14 mana §7more than
§7the previous, resetting after §a4s §7of
§7not firing.

§8§l* §8Co-op Soulbound §8§l*
§6§la §6§lLEGENDARY SWORD §6§la
""")
    val MOSQUITO_BOW_NAME = u("§6Mosquito Shortbow")
    val MOSQUITO_BOW = lines("""
§7Damage: §c+319
§7Strength: §c+151
§7Crit Damage: §9+39%
§7Vitality: §4+20
§7Shot Cooldown: §a0.5s

§d§lDuplex I
§7Shoot an extra arrow dealing §c4% §7of the
§7first arrow's damage.
§7Targets hit take §c1.1x §7fire damage
§7for §a60s§7.
§9Flame II
§7Arrows ignite your enemies for §a4s§7,
§7dealing §a6% §7of your damage per
§7second.

§6Ability: Eggsecute 
§7Arrows fired from this weapon deal
§c3x §7damage to §aEgg Sacs §7spawned by
§7the §cTarantula Broodfather§7!

§6Ability: Nasty Bite  §e§lLEFT CLICK
§7Shoot an enhanced shot, healing you
§7for §c189❤ Health §7on hit.
§8Vitality Cost: §410{UE028}

§6Shortbow: Instantly shoots!

§8This item can be reforged!
§6§lLEGENDARY BOW
""")
    val ENDER_PEARL_NAME = u("§fEnder Pearl")
    val ENDER_PEARL = lines("""
§8Collection Item

§f§lCOMMON
""")
    val SKYBLOCK_MENU_NAME = u("§aSkyBlock Menu §7(Click)")
    val SKYBLOCK_MENU = lines("""
§7View all of your SkyBlock progress,
§7including your Skills, Collections,
§7Recipes, and more!

§eClick to open!
""")
    val INFINITE_SPIRIT_LEAP_NAME = u("§5Infinileap")
    val INFINITE_SPIRIT_LEAP = lines("""
§c§l⦾ §6Ability: Spirit Leap  §e§lRIGHT CLICK
§7Allows you to teleport to any teammate! Grants
§a1 §7second of immunity after teleporting,
§7immunity is cancelled upon dealing damage.
§8Cooldown: §a2s

§cDungeons only!

§5§lEPIC DUNGEON ITEM
""")
    val FISHING_ROD_NAME = u("§fFishing Rod")
    val FISHING_ROD = lines("""
§7Damage: §c+15
§7Strength: §c+15
§7Fishing Speed: §b+5
§7Sea Creature Chance: §3+1%

§9ථ Hook §8§lNONE
§9ꨃ Line §8§lNONE
§9࿉ Sinker §8§lNONE

§7Talk to §2Roddy §7in the §2Backwater
§2Bayou §7to apply parts to this rod.

§8This item can be reforged!
§f§lCOMMON FISHING ROD
""")
    val STARRED_SPIRIT_MASK_NAME = u("§d{UE068} Necrotic Spirit Mask §6✪✪✪✪✪")
    val STARRED_SPIRIT_MASK = lines("""
§7Gear Score: §d2569
§7Health: §c+857.6
§7Defense: §a+480
§7Intelligence: §b+1,440 §9(+200)
§7Health Regen: §c+15.75
§7Speed: §f+160
§7Respiration: §3+384

§d§lWisdom V, §9Hecatomb VIII, §9Respiration IV
§9Respite V, §9Transylvanian V, §9Vampiric Vitality X

§6Ability: Second Wind 
§7Instead of dying, gain §f+50{UE022} Speed
§7and damage immunity for §a3 §7seconds.
§7Also heals you for §a10% §7of your §c❤
§cHealth §7over §a5 §7seconds.
§8Cooldown: §a30s

§d§la §d§lMYTHIC DUNGEON HELMET §d§la
""")
    val STARRED_BONZO_MASK_NAME = u("§5{UE068} Sunny Bonzo's Mask §6✪✪✪✪✪")
    val STARRED_BONZO_MASK = lines("""
§7Gear Score: §d2730
§7Health: §c+960
§7Defense: §a+640
§7Intelligence: §b+960
§7Health Regen: §c+15.75
§7Speed: §f+38.4 §9(+6)
§7Farming Wisdom: §3+6.2 §9(+4)

§d§lWisdom V, §9Hecatomb V, §9Respite V
§9Transylvanian V, §9Vampiric Vitality X

§6Ability: Clownin' Around 
§7Instead of dying, gain damage
§7immunity and §a+40 §c{UE00D} Strength §7for §a3s
§7and fully replenish your health. The
§7cooldown and Strength bonus you
§7receive improve based on your
§aDungeoneering Skill §7level. This ability
§7only works while in §cThe Catacombs§7!
§8Cooldown: §a180s

§5§la §5§lEPIC DUNGEON HELMET §5§la
""")
    val TERROR_CHESTPLATE_NAME = u("§6Spiked Terror Chestplate")
    val TERROR_CHESTPLATE = lines("""
§7Health: §c+238 §9(+8)
§7Defense: §a+73 §9(+8)
§7Strength: §c+10 §9(+10)
§7Crit Chance: §9+10% §9(+10%)
§7Crit Damage: §9+60% §9(+10%)
§7Attack Speed: §e+14% §9(+14%)
§7Intelligence: §b+15 §9(+10)
§7Speed: §f+13 §9(+1)
§7Gemstones: §8[§8⚔§8] §8[§8⚔§8]

§7Mana Regeneration II§7, §7Vitality II

§6Tiered Bonus: Hydra Strike ({T}/4)
§7Every §a0.2s§7, arrow attacks grant §c1§7
§7stack of §6⁑ Hydra Strike§7. §8Lose 1 stack
§8after {SEC}s of not gaining a stack.
§7
§7Each stack grants §c+{DMG}% Damage§7 and §b+1%
§bArrow Speed§7.
§7
§7At §c10§7 stacks shoot §a+2§7 arrows that deal §c20%
§cArrow Damage§8.

§6§lLEGENDARY CHESTPLATE
""")
    val TERROR_LEGGINGS_NAME = u("§6Spiked Terror Leggings")
    val TERROR_LEGGINGS = lines("""
§7Health: §c+213 §9(+8)
§7Defense: §a+63 §9(+8)
§7Strength: §c+10 §9(+10)
§7Crit Chance: §9+10% §9(+10%)
§7Crit Damage: §9+60% §9(+10%)
§7Attack Speed: §e+14% §9(+14%)
§7Intelligence: §b+15 §9(+10)
§7Speed: §f+13 §9(+1)
§7Gemstones: §8[§8⚔§8] §8[§8⚔§8]

§7Breeze II§7, §7Speed I

§6Tiered Bonus: Hydra Strike ({T}/4)
§7Every §a0.2s§7, arrow attacks grant §c1§7
§7stack of §6⁑ Hydra Strike§7. §8Lose 1 stack
§8after {SEC}s of not gaining a stack.
§7
§7Each stack grants §c+{DMG}% Damage§7 and §b+1%
§bArrow Speed§7.
§7
§7At §c10§7 stacks shoot §a+2§7 arrows that deal §c20%
§cArrow Damage§8.

§6§lLEGENDARY LEGGINGS
""")
    val TERROR_BOOTS_NAME = u("§6Spiked Terror Boots")
    val TERROR_BOOTS = lines("""
§7Health: §c+138 §9(+8)
§7Defense: §a+48 §9(+8)
§7Strength: §c+10 §9(+10)
§7Crit Chance: §9+10% §9(+10%)
§7Crit Damage: §9+60% §9(+10%)
§7Attack Speed: §e+14% §9(+14%)
§7Intelligence: §b+15 §9(+10)
§7Speed: §f+13 §9(+1)
§7Gemstones: §8[§8⚔§8] §8[§8⚔§8]

§7Blazing Resistance I§7, §7Experience I

§6Tiered Bonus: Hydra Strike ({T}/4)
§7Every §a0.2s§7, arrow attacks grant §c1§7
§7stack of §6⁑ Hydra Strike§7. §8Lose 1 stack
§8after {SEC}s of not gaining a stack.
§7
§7Each stack grants §c+{DMG}% Damage§7 and §b+1%
§bArrow Speed§7.
§7
§7At §c10§7 stacks shoot §a+2§7 arrows that deal §c20%
§cArrow Damage§8.

§6§lLEGENDARY BOOTS
""")
    val SPEED_WITHER_BOOTS_NAME = u("§dAncient Maxor's Boots §6✪✪✪✪✪§c➎")
    val SPEED_WITHER_BOOTS = lines("""
§7Gear Score: §d4935
§7Health: §c+2,188.8 §e(+60) §c[+40] §9(+7)
§7Defense: §a+812.8 §e(+30) §9(+7)
§7Strength: §c+428.8 §9(+35) §d(+32)
§7Crit Chance: §9+23.25% §9(+15%)
§7Crit Damage: §9+608% §9(+50%)
§7Intelligence: §b+224 §9(+25)
§7Health Regen: §c+15.75
§7Speed: §f+230.4
§7Gemstones: §6[§d⚔§6] §6[§d⚔§6]

§d§lLegion V, §9Depth Strider III, §9Feather Falling X
§9Growth VI, §9Protection VI, §9Respite V
§9Sugar Rush III, §9Vivacious Vitality VIII

§7Reduces the damage you take from
§7withers by §c10% §7and increases your
§7arrow damage by §c5%§7.

§6Full Set Bonus: Witherborn §7(0/4)
§7Spawns a wither minion every §e30
§7seconds up to a maximum §a1 §7wither.
§7Your withers will travel to and
§7explode on nearby enemies.

§9Ancient Bonus
§7Grants §a+1 §9{UE007} Crit Damage §7per
§cCatacombs §7level.

§d§la §d§lMYTHIC DUNGEON BOOTS §d§la
""")
    val SPEED_WITHER_LEGGINGS_NAME = u("§dNecrotic Maxor's Leggings §6✪✪✪✪✪")
    val SPEED_WITHER_LEGGINGS = lines("""
§7Gear Score: §d4015
§7Health: §c+1,472
§7Defense: §a+672
§7Crit Damage: §9+288%
§7Intelligence: §b+1,888 §9(+200) §d(+60)
§7Health Regen: §c+15.75
§7Speed: §f+192
§7Gemstones: §6[§b⚔§6] §6[§b⚔§6]

§d§lWisdom V, §9Respite V, §9Smarty Pants V
§9Vampiric Vitality V

§7Reduces the damage you take from
§7withers by §c10% §7and increases your
§7arrow damage by §c5%§7.

§6Full Set Bonus: Witherborn §7(0/4)
§7Spawns a wither minion every §e30
§7seconds up to a maximum §a1 §7wither.
§7Your withers will travel to and
§7explode on nearby enemies.

§d§la §d§lMYTHIC DUNGEON LEGGINGS §d§la
""")
    val MITHRIL_COAT_NAME = u("§6Necrotic Mithril Coat")
    val MITHRIL_COAT = lines("""
§7Defense: §a+125
§7Intelligence: §b+150 §9(+150)
§7Speed: §f+15

§6Ability: Mithril's Protection 
§7Any damage taken is max §a40% §7of the wearer's
§c❤ Health§7. Gain §cRegeneration §7when this ability
§7activates.

§6§la §6§lLEGENDARY CHESTPLATE §6§la
""")
    val WISE_WITHER_BOOTS_NAME = u("§dNecrotic Storm's Boots §6✪✪✪✪✪")
    val WISE_WITHER_BOOTS = lines("""
§7Gear Score: §d4175
§7Health: §c+1,408
§7Defense: §a+544
§7Intelligence: §b+3,264 §9(+200) §d(+60)
§7Health Regen: §c+15.75
§7Speed: §f+38.4
§7Gemstones: §6[§b{UE003}§6] §6[§b⚔§6]

§d§lWisdom V, §9Depth Strider III, §9Feather Falling X
§9Growth V, §9Protection V, §9Respite V
§9Sugar Rush III, §9Vampiric Vitality V

§7Reduces the damage you take from
§7withers by §c10%§7.

§6Full Set Bonus: Witherborn §7(3/4)
§7Spawns a wither minion every §e30
§7seconds up to a maximum §a1 §7wither.
§7Your withers will travel to and
§7explode on nearby enemies.

§d§la §d§lMYTHIC DUNGEON BOOTS §d§la
""")
    val WISE_WITHER_LEGGINGS_NAME = u("§dNecrotic Storm's Leggings §6✪✪✪✪✪")
    val WISE_WITHER_LEGGINGS = lines("""
§7Gear Score: §d5000
§7Health: §c+2,336 §e(+60)
§7Defense: §a+992 §e(+30)
§7Intelligence: §b+3,424 §9(+200) §d(+60)
§7Health Regen: §c+15.75
§7Gemstones: §6[§b{UE003}§6] §6[§b⚔§6]

§d§lWisdom V, §9Growth V, §9Protection V
§9Respite V, §9Smarty Pants V

§7Reduces the damage you take from
§7withers by §c10%§7.

§6Full Set Bonus: Witherborn §7(3/4)
§7Spawns a wither minion every §e30
§7seconds up to a maximum §a1 §7wither.
§7Your withers will travel to and
§7explode on nearby enemies.

§d§la §d§lMYTHIC DUNGEON LEGGINGS §d§la
""")
    val WISE_WITHER_CHESTPLATE_NAME = u("§dLoving Storm's Chestplate §6✪✪✪✪✪")
    val WISE_WITHER_CHESTPLATE = lines("""
§7Gear Score: §d5000
§7Health: §c+2,617.6 §e(+60) §9(+14)
§7Defense: §a+1,177.6 §e(+30) §9(+14)
§7True Defense: §f+15.5
§7Intelligence: §b+2,816 §9(+120) §d(+60)
§7Health Regen: §c+15.75
§7Gemstones: §6[§b{UE003}§6] §6[§b⚔§6]

§d§lWisdom V, §9Growth V, §9Protection V
§9Reflection V, §9Respite V, §9True Protection I

§7Reduces the damage you take from
§7withers by §c10%§7.

§6Full Set Bonus: Witherborn §7(3/4)
§7Spawns a wither minion every §e30
§7seconds up to a maximum §a1 §7wither.
§7Your withers will travel to and
§7explode on nearby enemies.

§9Loving Bonus
§7Increases ability damage by §a5%§7.

§d§la §d§lMYTHIC DUNGEON CHESTPLATE §d§la
""")
    val WISE_WITHER_HELMET_NAME = u("§dShiny Ancient Storm's Helmet §6✪✪✪✪✪§c➊")
    val WISE_WITHER_HELMET = lines("""
§7Gear Score: §d5000
§7Health: §c+1,932.8 §9(+7)
§7Defense: §a+684.8 §9(+7)
§7Strength: §c+224 §9(+35)
§7Crit Chance: §9+23.25% §9(+15%)
§7Crit Damage: §9+396.8% §9(+50%) §d(+12%)
§7Intelligence: §b+3,072 §9(+25) §d(+30)
§7Health Regen: §c+5.25
§7Gemstones: §6[§b{UE003}§6] §6[§8⚔§6]

§d§lLegion V, §9Big Brain V, §9Growth V
§9Hecatomb X, §9Protection V, §9Rejuvenate V
§9Strong Vitality V

§7Reduces the damage you take from
§7withers by §c10%§7.

§6Full Set Bonus: Witherborn §7(3/4)
§7Spawns a wither minion every §e30
§7seconds up to a maximum §a1 §7wither.
§7Your withers will travel to and
§7explode on nearby enemies.

§9Ancient Bonus
§7Grants §a+1 §9{UE007} Crit Damage §7per
§cCatacombs §7level.

§d§la §d§lSHINY MYTHIC DUNGEON HELMET §d§la
""")
    val RACING_HELMET_NAME = u("§dRenowned Racing Helmet")
    val RACING_HELMET = lines("""
§7Health: §c+47 §9(+10)
§7Defense: §a+10 §9(+10)
§7Strength: §c+12 §9(+12)
§7Crit Chance: §9+12% §9(+12%)
§7Crit Damage: §9+12% §9(+12%)
§7Attack Speed: §e+15% §9(+15%)
§7Intelligence: §b+12 §9(+12)
§7Speed: §f+400 §9(+1)

§d§lWisdom V
§7Gain §b5 §7Intelligence for every §b5
§7levels of exp you have on you.
§7Capped at §b100 §7Intelligence.
§9Hecatomb IX §894
§7Gain §a+0.92% §cCatacombs §7XP & §a+1.84% §3Class §7XP,
§7doubled §7on §b§lS+ §7runs.
§7Grants §c+7.4{UE010} §7per 10 §cCatacombs §7levels.
§8100 S runs to tier up!

§7Grants §f+100{UE022} Speed Cap§7.

§8When horses are not fast enough,
§8use a Racing Helmet instead.

§7Purchased by: §6[MVP§9++§6] Player§f
§7Purchased for: §64,545,454,545 Coins

§8Auction #18
§8Bid #2
§8September 2025

§9Renowned Bonus
§7Increases all §cCombat §7stats and §b{UE01A}
§bMagic Find §7by §a+1%§7.

§d§la §d§lMYTHIC HELMET §d§la
""")
}
