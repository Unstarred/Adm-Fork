package com.engineerclient.features.mapprediction

object MapStats {
    val COMBOS = arrayOf(
        intArrayOf(1, 2, 1, 1, 2, 849),
        intArrayOf(2, 1, 1, 2, 1, 701),
        intArrayOf(1, 2, 0, 2, 2, 619),
        intArrayOf(2, 1, 2, 1, 1, 592),
        intArrayOf(1, 2, 2, 0, 2, 385),
        intArrayOf(2, 2, 1, 2, 0, 251),
        intArrayOf(2, 0, 2, 1, 2, 236),
        intArrayOf(2, 0, 1, 2, 2, 191),
        intArrayOf(1, 2, 1, 2, 1, 125),
        intArrayOf(1, 1, 2, 1, 2, 114),
        intArrayOf(2, 2, 2, 1, 0, 113),
        intArrayOf(1, 1, 1, 2, 2, 99),
        intArrayOf(1, 2, 2, 1, 1, 63),
        intArrayOf(2, 0, 2, 2, 1, 51),
        intArrayOf(2, 1, 2, 2, 0, 33),
        intArrayOf(1, 1, 2, 2, 1, 20),
        intArrayOf(0, 2, 1, 2, 2, 11),
        intArrayOf(1, 0, 2, 2, 2, 6),
        intArrayOf(1, 2, 2, 2, 0, 4),
        intArrayOf(0, 2, 2, 1, 2, 2),
    )

    val WALK = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.023f, 0.06f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

    val BLOOD_FROM_ENTRANCE = floatArrayOf(0f, 0.244f, 0.704f, 0.36f, 0.321f, 0.633f, 0.707f, 0.766f, 0.905f, 1f, 0.674f, 0.674f, 0.674f, 0.674f, 0.674f, 0.674f)

    val FAIRY_FROM_ENTRANCE = floatArrayOf(0f, 0.029f, 0.46f, 1f, 0.89f, 0.638f, 0.596f, 0.739f, 0.739f, 0.739f, 0.739f, 0.739f, 0.739f, 0.739f, 0.739f, 0.739f)

    val BIG_TILE = floatArrayOf(
        0.762f, 0.728f, 1f, 0.828f, 0.558f, 0.73f,
        0.795f, 0.722f, 0.728f, 0.548f, 0.519f, 0.628f,
        0.674f, 0.646f, 0.764f, 0.523f, 0.466f, 0.509f,
        0.656f, 0.564f, 0.514f, 0.574f, 0.494f, 0.555f,
        0.585f, 0.51f, 0.447f, 0.582f, 0.493f, 0.54f,
        0.72f, 0.648f, 0.476f, 0.566f, 0.466f, 0.694f,
    )

    val CALIBRATION = arrayOf(
        doubleArrayOf(0.8835, 0.8623, 0.8383, 0.8911, 0.8757, 0.9302, 0.9268, 0.9375),
        doubleArrayOf(0.1616, -0.0055, -0.0908, -0.1025, -0.1381, 0.0736, 0.0567, 0.0450),
        doubleArrayOf(-0.0212, -0.0107, 0.0095, 0.0098, 0.0148, 0.0206, -0.0228, 0.0000),
    )

    const val FAIRY_BEFORE_BLOOD = 4

    const val RARE_RATE = 0.25

    const val POOL_1X1 = 5
    const val POOL_PUZZLE = 6
    const val POOL_TRAP = 7
    const val POOL_YELLOW = 8
    const val POOL_RARE = 9
    const val POOL_BLOOD = 10
    const val POOL_FAIRY = 11
    const val POOL_ENTRANCE = 12
    const val POOLS = 13

    val NAMES = listOf(
        RoomName("Bridges", 0, 6, 509),
        RoomName("Gold", 0, 1, 504),
        RoomName("Crypt", 0, 5, 489),
        RoomName("Balcony", 0, 4, 480),
        RoomName("Mage", 0, 4, 479),
        RoomName("Grass Ruin", 0, 3, 477),
        RoomName("Redstone Warrior", 0, 3, 473),
        RoomName("Archway", 0, 3, 465),
        RoomName("Pedestal", 0, 5, 462),
        RoomName("Purple Flags", 0, 5, 461),
        RoomName("Doors", 0, 5, 461),
        RoomName("Pressure Plates", 0, 6, 457),
        RoomName("Skull", 0, 2, 455),
        RoomName("Grand Library", 0, 4, 448),
        RoomName("Deathmite", 1, 6, 967),
        RoomName("Catwalk", 1, 6, 943),
        RoomName("Gravel", 1, 6, 919),
        RoomName("Diagonal", 1, 4, 912),
        RoomName("Red Blue", 1, 4, 903),
        RoomName("Slime", 1, 5, 900),
        RoomName("Wizard", 1, 4, 890),
        RoomName("Hallway", 2, 3, 960),
        RoomName("Waterfall", 2, 8, 942),
        RoomName("Mossy", 2, 4, 919),
        RoomName("Pit", 2, 5, 915),
        RoomName("Quartz Knight", 2, 7, 899),
        RoomName("Pipes", 2, 7, 888),
        RoomName("Buttons", 3, 5, 723),
        RoomName("Stairs", 3, 4, 714),
        RoomName("Cathedral", 3, 8, 710),
        RoomName("Flags", 3, 7, 707),
        RoomName("Museum", 3, 5, 707),
        RoomName("Supertall", 3, 6, 697),
        RoomName("Rails", 3, 9, 688),
        RoomName("Mines", 3, 10, 671),
        RoomName("Atlas", 3, 6, 632),
        RoomName("Dino Site", 4, 4, 642),
        RoomName("Lava Ravine", 4, 6, 626),
        RoomName("Pirate", 4, 6, 616),
        RoomName("Layers", 4, 8, 610),
        RoomName("Market", 4, 5, 604),
        RoomName("Spider", 4, 9, 601),
        RoomName("Withermancer", 4, 4, 599),
        RoomName("Melon", 4, 7, 596),
        RoomName("Chambers", 4, 5, 587),
        RoomName("Well", 4, 7, 584),
        RoomName("Altar", 4, 6, 538),
        RoomName("Double Diamond", 5, 3, 613),
        RoomName("Andesite", 5, 2, 607),
        RoomName("Chains", 5, 2, 607),
        RoomName("Basement", 5, 1, 607),
        RoomName("Long Hall", 5, 3, 605),
        RoomName("Mushroom", 5, 1, 604),
        RoomName("Scaffolding", 5, 2, 603),
        RoomName("Painting", 5, 2, 602),
        RoomName("Mural", 5, 1, 596),
        RoomName("Staircase", 5, 3, 594),
        RoomName("Small Waterfall", 5, 2, 589),
        RoomName("Redstone Crypt", 5, 3, 587),
        RoomName("Prison Cell", 5, 1, 585),
        RoomName("Perch", 5, 2, 585),
        RoomName("Blue Skulls", 5, 1, 579),
        RoomName("Raccoon", 5, 4, 578),
        RoomName("Zodd", 5, 1, 570),
        RoomName("Locked Away", 5, 1, 567),
        RoomName("Tomioka", 5, 0, 555),
        RoomName("Big Red Flag", 5, 2, 551),
        RoomName("Red Green", 5, 3, 494),
        RoomName("Overgrown", 5, 3, 485),
        RoomName("Temple", 5, 3, 484),
        RoomName("Leaves", 5, 1, 457),
        RoomName("Sloth", 5, 1, 450),
        RoomName("Dome", 5, 2, 450),
        RoomName("Cage", 5, 1, 449),
        RoomName("Beams", 5, 2, 446),
        RoomName("Dip", 5, 2, 439),
        RoomName("Banners", 5, 1, 430),
        RoomName("Duncan", 5, 1, 411),
        RoomName("Lots Of Floors", 5, 3, 401),
        RoomName("Steps", 5, 1, 375),
        RoomName("Small Stairs", 5, 2, 370),
        RoomName("Dueces", 5, 3, 365),
        RoomName("Ritual", 5, 3, 363),
        RoomName("Arrow Trap", 5, 1, 361),
        RoomName("Jumping Skulls", 5, 1, 359),
        RoomName("Cell", 5, 1, 358),
        RoomName("Silver Sword", 5, 1, 354),
        RoomName("Sarcophagus", 5, 3, 351),
        RoomName("Hall", 5, 0, 350),
        RoomName("Water", 5, 2, 350),
        RoomName("Admin", 5, 0, 348),
        RoomName("Overgrown Chains", 5, 2, 344),
        RoomName("Mirror", 5, 1, 343),
        RoomName("Cages", 5, 2, 341),
        RoomName("End", 5, 2, 339),
        RoomName("Slabs", 5, 2, 335),
        RoomName("Redstone Key", 5, 3, 331),
        RoomName("Spikes", 5, 3, 331),
        RoomName("Drop", 5, 2, 326),
        RoomName("Criss-Cross", 5, 1, 320),
        RoomName("Granite", 5, 2, 319),
        RoomName("Cobble Wall Pillar", 5, 2, 109),
        RoomName("Knight", 5, 3, 98),
        RoomName("Multicolored", 5, 1, 96),
        RoomName("Black Flag", 5, 3, 96),
        RoomName("Logs", 5, 4, 94),
        RoomName("Carpets", 5, 1, 93),
        RoomName("Quad Lava", 5, 2, 89),
        RoomName("Golden Oasis", 5, 1, 78),
        RoomName("Ice Fill", 6, 0, 1366),
        RoomName("Creeper Beams", 6, 0, 1337),
        RoomName("Three Weirdos", 6, 0, 1336),
        RoomName("Ice Path", 6, 0, 1333),
        RoomName("Quiz", 6, 0, 1329),
        RoomName("Water Board", 6, 0, 1321),
        RoomName("Teleport Maze", 6, 0, 1319),
        RoomName("Tic Tac Toe", 6, 1, 1294),
        RoomName("Boulder", 6, 0, 1266),
        RoomName("Lower Blaze", 6, 1, 1191),
        RoomName("Higher Blaze", 6, 1, 1108),
        RoomName("New Trap", 7, 3, 2931),
        RoomName("Old Trap", 7, 4, 1563),
        RoomName("Default", 8, 0, 1746),
        RoomName("Shadow Assassin", 8, 0, 945),
        RoomName("King Midas", 8, 0, 903),
        RoomName("Dragon", 8, 0, 900),
        RoomName("Vinny 8 Ball", 9, 1, 20),
        RoomName("Stone Window", 9, 2, 13),
        RoomName("Rare Pillars", 9, 1, 12),
        RoomName("Rare Overgrown", 9, 3, 12),
        RoomName("Lava Pit", 9, 3, 11),
        RoomName("Trinity", 9, 4, 9),
        RoomName("Rail Track", 9, 3, 8),
        RoomName("Tombstone", 9, 2, 8),
        RoomName("Three Floors", 9, 1, 8),
        RoomName("Sand Dragon", 9, 1, 7),
        RoomName("Rare Carpets", 9, 1, 1),
        RoomName("Blood", 10, 0, 4494),
        RoomName("Fairy", 11, 0, 4494),
        RoomName("Entrance", 12, 0, 4494),
    )

    fun meanSecrets(skip: Set<String> = emptySet()): DoubleArray = DoubleArray(POOLS) { p ->
        val left = NAMES.filter { it.pool == p && it.name !in skip }.ifEmpty { NAMES.filter { it.pool == p } }
        val seen = left.sumOf { it.seen.toDouble() }
        if (seen > 0) left.sumOf { it.secrets * it.seen.toDouble() } / seen else 0.0
    }
}

class RoomName(val name: String, val pool: Int, val secrets: Int, val seen: Int)
