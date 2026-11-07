package com.engineerclient.features.chat

import com.engineerclient.util.noFormatting
import com.google.gson.GsonBuilder
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class ChatRules(val custom: List<Custom>, val block: List<Block>) {

    data class Custom(val regex: String = "", val action: String = "block")
    data class Block(val regex: String = "", val template: String = "", val family: String = "")

    private class Compiled(val pattern: Pattern, val block: Boolean, val label: String, val anchored: Boolean)

    private val compiled: List<Compiled>
    val invalid: List<String>

    init {
        val ok = ArrayList<Compiled>()
        val bad = ArrayList<String>()
        for (c in custom) {
            try { ok += Compiled(Pattern.compile(c.regex), c.action == "block", "custom ${c.regex}", anchored = false) }
            catch (e: PatternSyntaxException) { bad += c.regex }
        }
        for (b in block) {
            try { ok += Compiled(Pattern.compile(b.regex), true, b.template, anchored = true) }
            catch (e: PatternSyntaxException) { bad += b.regex }
        }
        compiled = ok
        invalid = bad
    }

    val size: Int get() = compiled.size

    fun hides(text: String): String? {
        for (r in compiled) {
            val m = r.pattern.matcher(text)
            val hit = if (r.anchored) m.matches() else m.find()
            if (hit) return if (r.block) r.label else null
        }
        return null
    }

    companion object {
        private val gson = GsonBuilder().create()
        private val repeatCounter = Regex("""\s\(\d+\)$""")

        fun strip(text: String): String = repeatCounter.replace(text.noFormatting().trim(), "")

        fun parse(json: String): ChatRules {
            val file = gson.fromJson(json, RulesFile::class.java) ?: RulesFile()
            return ChatRules(file.custom, file.block)
        }

        fun bundled(): ChatRules {
            val text = ChatRules::class.java.getResourceAsStream("/chat/hidden.json")?.bufferedReader()?.use { it.readText() }
                ?: return ChatRules(emptyList(), emptyList())
            return parse(text)
        }
    }

    private class RulesFile(
        val version: Int = 0,
        val custom: List<Custom> = emptyList(),
        val block: List<Block> = emptyList(),
    )
}
