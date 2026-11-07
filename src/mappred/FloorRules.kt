package com.engineerclient.mappred

class FloorRules(
    val floor: Int,
    val w: Int,
    val h: Int,
    val puzzleColumn: Boolean,
    val deadEndTarget: Int,
    val trap: Boolean,
    val fairyBeforeBlood: Int,
    val combos: Array<IntArray>,
    val walk: FloatArray,
    val bloodFromEntrance: FloatArray,
    val fairyFromEntrance: FloatArray,
    val bigTile: FloatArray?,
    val missingNames: Set<String>,
    val puzzleOrder: Boolean = false,
    val bunch: Double = 0.0,
    val anchorWeights: Array<Array<FloatArray>>? = null,
    val reserve: Int = 0,
    val calibration: Array<DoubleArray>? = null,
    val gameMixes: Array<IntArray> = combos,
    val gameMixRate: DoubleArray = DoubleArray(0),
    val gameCalibration: Array<DoubleArray>? = null,
) {
    val bloodWalk = fairyBeforeBlood * 2 + 1

    val maxBig = combos.maxOf { c -> (0 until 5).sumOf { c[it] } }

    companion object {
        val F1 = FloorRules(1, 4, 5, false, 0, false, 3, FloorStats.COMBOS_1, FloorStats.WALK_SHORT,
            FloorStats.BLOOD_FROM_ENTRANCE_1, FloorStats.FAIRY_FROM_ENTRANCE_1, null, FloorStats.MISSING_1, gameMixRate = FloorStats.MIX_RATE_1)
        val F2 = FloorRules(2, 5, 5, false, 0, false, 3, FloorStats.COMBOS_2, FloorStats.WALK_SHORT,
            FloorStats.BLOOD_FROM_ENTRANCE_2_4, FloorStats.FAIRY_FROM_ENTRANCE_2_4, null, FloorStats.MISSING_2, gameMixRate = FloorStats.MIX_RATE_2)
        val F3 = FloorRules(3, 5, 5, false, 0, true, 3, FloorStats.COMBOS_3_4, FloorStats.WALK_SHORT,
            FloorStats.BLOOD_FROM_ENTRANCE_2_4, FloorStats.FAIRY_FROM_ENTRANCE_2_4, null, FloorStats.MISSING_3, gameMixRate = FloorStats.MIX_RATE_3_4)
        val F4 = FloorRules(4, 5, 5, true, 6, true, 3, FloorStats.COMBOS_3_4, FloorStats.WALK_SHORT,
            FloorStats.BLOOD_FROM_ENTRANCE_2_4, FloorStats.FAIRY_FROM_ENTRANCE_2_4, null, FloorStats.MISSING_4, gameMixRate = FloorStats.MIX_RATE_3_4)
        val F5 = FloorRules(5, 5, 6, true, 5, true, 4, FloorStats.COMBOS_5_6, MapStats.WALK,
            FloorStats.BLOOD_FROM_ENTRANCE_5_6, FloorStats.FAIRY_FROM_ENTRANCE_5_6, null, FloorStats.MISSING_5, gameMixRate = FloorStats.MIX_RATE_5_6)
        val F6 = FloorRules(6, 5, 6, true, 5, true, 4, FloorStats.COMBOS_5_6, MapStats.WALK,
            FloorStats.BLOOD_FROM_ENTRANCE_5_6, FloorStats.FAIRY_FROM_ENTRANCE_5_6, null, FloorStats.MISSING_6, gameMixRate = FloorStats.MIX_RATE_5_6)
        val F7 = FloorRules(7, 6, 6, false, 0, true, MapStats.FAIRY_BEFORE_BLOOD, MapStats.COMBOS, MapStats.WALK,
            MapStats.BLOOD_FROM_ENTRANCE, MapStats.FAIRY_FROM_ENTRANCE, MapStats.BIG_TILE, emptySet(), puzzleOrder = true, reserve = 5,
            calibration = MapStats.CALIBRATION, gameMixes = GameStats.MIXES, gameMixRate = GameStats.MIX_RATE, gameCalibration = GameSampler.CALIBRATION)

        fun of(floor: Int): FloorRules? = when (floor) {
            1 -> F1; 2 -> F2; 3 -> F3; 4 -> F4; 5 -> F5; 6 -> F6; 7 -> F7
            else -> null
        }
    }
}
