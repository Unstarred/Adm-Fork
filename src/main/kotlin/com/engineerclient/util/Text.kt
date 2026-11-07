package com.engineerclient.util

val FORMATTING = Regex("§.")

fun String.noFormatting(): String = replace(FORMATTING, "")
