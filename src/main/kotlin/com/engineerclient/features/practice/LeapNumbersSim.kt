package com.engineerclient.features.practice

import com.engineerclient.EngineerClient
import com.odtheking.odin.utils.itemId

object LeapNumbersSim {

    @JvmStatic
    fun open(): Boolean {
        val player = EngineerClient.mc.player ?: return false
        if (EngineerClient.mc.gui.screen() != null || player.mainHandItem.itemId != "INFINITE_SPIRIT_LEAP") return false
        InfNumbersSim.open(0L)
        return true
    }
}
