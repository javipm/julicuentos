package com.julicuentos.app.persist

/**
 * Minimal tolerant JSON reader/writer for the player persistence schema
 * (tasks S5.5; design.md D9 wanted org.json for "the two JSON spots", but the
 * platform org.json is not available inside JVM unit tests offline, and the
 * parser is a required unit-test surface — so this ~150-line, zero-dependency
 * parser fills that role. Catalog parsing keeps org.json.
 *
 * This is NOT a general JSON library: it understands exactly the value kinds the
 * schema needs (objects, arrays, strings, numbers, null, booleans) and drops
 * anything else. Every entry point is defensive — malformed input returns null /
 * safe defaults instead of throwing, which is precisely the "validator-first"
 * requirement of specs/persistence "Tolerant parser with safe defaults".
 */
internal object MiniJson {

    /** Parses [text] as a JSON object; null when the document is not an object
     *  or is malformed at any point. Trailing content after the object is ignored. */
    fun parseObject(text: String): Map<String, Any?>? {
        val p = Parser(text)
        p.skipWs()
        val node = p.parseValue()
        if (node === FAILED) return null
        val map = node as? Map<*, *> ?: return null
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in map) {
            val key = k as? String ?: return null
            out[key] = v
        }
        return out
    }

    /** Serialises a flat object whose values are String?, Long, Boolean, List<String>,
     *  or a pre-serialised [Raw] sub-object. */
    fun writeObject(entries: List<Pair<String, Any?>>): String {
        val body = entries.joinToString(",") { (key, value) ->
            "\"${escape(key)}\":${writeValue(value)}"
        }
        return "{$body}"
    }

    /** Wrapper carrying an already-serialised JSON fragment (nested timer object). */
    internal class Raw(val text: String)
    internal fun raw(text: String): Any = Raw(text)

    private fun writeValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${escape(value)}\""
        is Long -> value.toString()
        is Int -> value.toString()
        is Boolean -> value.toString()
        is List<*> -> value.joinToString(",", "[", "]") { writeValue(it) }
        is Raw -> value.text
        else -> "null"
    }

    /** JSON string escaping (the only place backslashes/quotes/controls matter). */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Sentinel distinguishing "malformed" from a legal JSON null (null is a legal
     *  value, so parse failures can't be represented as plain null in this API). */
    private object FAILED

    private class Parser(private val text: String) {
        private var pos = 0

        fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        /** Returns FAILED on malformed input; null for JSON null; else the value. */
        fun parseValue(): Any? {
            skipWs()
            if (pos >= text.length) return FAILED
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?>? {
            pos++ // '{'
            skipWs()
            if (pos < text.length && text[pos] == '}') { pos++; return emptyMap() }
            val out = LinkedHashMap<String, Any?>()
            while (true) {
                skipWs()
                val key = parseString() ?: return null
                skipWs()
                if (pos >= text.length || text[pos] != ':') return null
                pos++
                val value = parseValue()
                if (value === FAILED) return null
                out[key] = value
                skipWs()
                if (pos >= text.length) return null
                when (text[pos]) {
                    ',' -> { pos++; continue }
                    '}' -> { pos++; return out }
                    else -> return null
                }
            }
        }

        private fun parseArray(): List<Any?>? {
            pos++ // '['
            skipWs()
            if (pos < text.length && text[pos] == ']') { pos++; return emptyList() }
            val out = ArrayList<Any?>()
            while (true) {
                skipWs()
                val value = parseValue()
                if (value === FAILED) return null
                out.add(value)
                skipWs()
                if (pos >= text.length) return null
                when (text[pos]) {
                    ',' -> { pos++; continue }
                    ']' -> { pos++; return out }
                    else -> return null
                }
            }
        }

        private fun parseString(): String? {
            if (pos >= text.length || text[pos] != '"') return null
            pos++
            val sb = StringBuilder()
            while (pos < text.length) {
                val c = text[pos]
                when {
                    c == '"' -> { pos++; return sb.toString() }
                    c == '\\' -> {
                        pos++
                        if (pos >= text.length) return null
                        when (val esc = text[pos]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 >= text.length) return null
                                val hex = text.substring(pos + 1, pos + 5)
                                val code = hex.toIntOrNull(16) ?: return null
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> return null
                        }
                        pos++
                    }
                    else -> { sb.append(c); pos++ }
                }
            }
            return null // unterminated
        }

        private fun parseNumber(): Any? {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".-+eE")) pos++
            if (pos == start) return FAILED
            val raw = text.substring(start, pos)
            val asLong = raw.toLongOrNull()
            if (asLong != null) return asLong
            val asDouble = raw.toDoubleOrNull()
            return if (asDouble != null && asDouble.isFinite()) asDouble else FAILED
        }

        private fun parseBoolean(): Any? {
            if (text.startsWith("true", pos)) { pos += 4; return true }
            if (text.startsWith("false", pos)) { pos += 5; return false }
            return FAILED
        }

        private fun parseNull(): Any? {
            if (text.startsWith("null", pos)) { pos += 4; return null }
            return FAILED
        }
    }
}