package com.engineerclient.util

/**
 * Every § and the character after it, not just the legacy colour and format codes.
 *
 * Hypixel salts some text with a § followed by a letter outside the legacy set, mid-word: a scoreboard can't hold two
 * identical lines, so a real sidebar says "Early Summer 19§wth" and "The Catac§uombs (F7)", and one line is nothing but
 * salt ("§j", a blank spacer). Stripping only the legacy codes would leave that in, and a pattern matched against the
 * text would miss. Compiled once, for every caller: chat lines, names, sidebar lines and split rows go through it
 * every tick or every frame.
 */
val FORMATTING = Regex("§.")

/** This text with every § code gone ([FORMATTING]): the plain text players read. */
fun String.noFormatting(): String = replace(FORMATTING, "")
