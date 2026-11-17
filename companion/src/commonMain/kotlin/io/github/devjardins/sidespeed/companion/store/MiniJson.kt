package io.github.devjardins.sidespeed.companion.store

/**
 * The little JSON the store needs, so the companion carries no serialization plugin. Values are
 * `Map<String, Any?>`, `List<Any?>`, `String`, `Long`, `Double`, `Boolean` or `null`.
 */
internal object MiniJson {
    class Malformed(message: String) : Exception(message)

    /** @throws Malformed */
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (!p.atEnd()) throw Malformed("Trailing characters at ${p.pos}")
        return v
    }

    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is Boolean, is Int, is Long -> out.append(value.toString())
            is String -> writeString(value, out)
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    writeString(k as String, out)
                    out.append(':')
                    write(v, out)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(v, out)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("Unsupported JSON value: ${value::class}")
        }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u").append(c.code.toString(16).padStart(4, '0')) else out.append(c)
            }
        }
        out.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos] in " \t\r\n") pos++
        }

        private fun fail(what: String): Nothing = throw Malformed("$what at $pos")

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("Too deeply nested")
            if (atEnd()) fail("Unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("Unexpected '$c'")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("Expected $word")
            pos += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            pos++
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return m }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') fail("Expected a key")
                val k = str()
                skipWs()
                if (atEnd() || s[pos] != ':') fail("Expected ':'")
                pos++
                skipWs()
                m[k] = value(depth + 1)
                skipWs()
                if (atEnd()) fail("Unexpected end")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> { pos--; fail("Expected ',' or '}'") }
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            pos++
            val l = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return l }
            while (true) {
                skipWs()
                l += value(depth + 1)
                skipWs()
                if (atEnd()) fail("Unexpected end")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> { pos--; fail("Expected ',' or ']'") }
                }
            }
        }

        private fun str(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("Unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("Unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("Short \\u escape")
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: fail("Bad \\u escape")
                                if (hex.any { it == '+' || it == '-' }) fail("Bad \\u escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("Bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("Control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos] in '0'..'9' || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            return t.toLongOrNull() ?: t.toDoubleOrNull()?.takeIf { it.isFinite() } ?: fail("Bad number '$t'")
        }

        private companion object {
            const val MAX_DEPTH = 32
        }
    }
}
