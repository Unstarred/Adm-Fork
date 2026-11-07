package com.engineerclient.features.pov

import net.fabricmc.loader.api.FabricLoader

object SodiumBridge {

    val available: Boolean = FabricLoader.getInstance().isModLoaded("sodium")
}
