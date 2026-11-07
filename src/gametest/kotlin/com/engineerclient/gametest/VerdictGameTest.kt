package com.engineerclient.gametest

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/**
 * Runs last: fails the run with every other test's failures at once. Each test only records its own ([Checks]), since
 * the harness stops at the first test that throws and the rest would never run.
 */
class VerdictGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        if (Checks.all.isNotEmpty()) throw AssertionError("${Checks.all.size} failed:\n" + Checks.all.joinToString("\n"))
    }
}
