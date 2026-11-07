package com.engineerclient.gametest

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

class VerdictGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        if (Checks.all.isNotEmpty()) throw AssertionError("${Checks.all.size} failed:\n" + Checks.all.joinToString("\n"))
    }
}
