package com.engineerclient.gametest

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Every problem ADM causes while the gametests run: anything ADM's own logger says at WARN or above, and anything
 * logged at WARN or above whose exception went through ADM's code (its classes, or its mixin handlers merged into
 * Minecraft's and Odin's, which keep `ec$` in their names). Attached before the game starts (a pre-launch
 * entrypoint), so startup counts too. The tests read it per phase: a phase passes only with nothing new here.
 */
object ProblemLog : PreLaunchEntrypoint {
    data class Problem(val level: String, val thread: String, val logger: String, val message: String, val thrown: String?) {
        override fun toString() = "[$level] [$thread] $logger: $message" + (thrown?.let { "\n      $it" } ?: "")
    }

    private val problems = CopyOnWriteArrayList<Problem>()

    override fun onPreLaunch() {
        val context = LogManager.getContext(false) as LoggerContext
        val appender = object : AbstractAppender("adm-gametest-problems", null, null, true, Property.EMPTY_ARRAY) {
            override fun append(event: LogEvent) {
                if (!fromAdm(event)) return
                val t = event.thrown
                problems += Problem(event.level.name(), event.threadName, event.loggerName, event.message.formattedMessage,
                    t?.let { e -> "$e" + (e.stackTrace.firstOrNull { admFrame(it) }?.let { " at $it" } ?: "") })
            }
        }
        appender.start()
        context.configuration.rootLogger.addAppender(appender, Level.WARN, null)
        context.updateLoggers()
    }

    private fun admFrame(f: StackTraceElement) = f.className.startsWith("com.engineerclient.") || f.methodName.contains("ec$")

    /**
     * ADM's own warnings always count. Another logger's counts when its exception went through ADM's code, except a
     * network failure another library logged and handled itself: the dev client is offline (no Mojang session), so
     * authlib warning that a skin lookup ADM started timed out is the environment, not ADM. ADM's own handling of the
     * same failure still logs on its own logger, and counts.
     */
    private fun fromAdm(event: LogEvent): Boolean {
        if (event.loggerName == "engineerclient") return true
        val chain = generateSequence(event.thrown) { it.cause }.toList()
        if (chain.none { t -> t.stackTrace.any(::admFrame) }) return false
        val network = chain.any { it is IOException || it.javaClass.name.endsWith("MinecraftClientException") }
        return !(network && event.level.isLessSpecificThan(Level.WARN))
    }

    /** How many problems there have been so far: a phase's start. */
    fun mark(): Int = problems.size

    /** The problems since [mark]. */
    fun since(mark: Int): List<Problem> = problems.drop(mark)
}
