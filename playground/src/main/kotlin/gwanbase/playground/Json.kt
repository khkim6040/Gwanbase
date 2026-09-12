package gwanbase.playground

/**
 * 의존성 없이 응답 JSON을 만드는 최소 인코더.
 *
 * 요청 본문은 SQL 텍스트를 그대로 받으므로 디코더는 필요 없다.
 * `Map`은 객체, `Iterable`은 배열, 문자열·숫자·불리언·null은 리터럴, 그 외는 `toString()` 문자열이다.
 */
object Json {

    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean, is Int, is Long, is Short, is Byte -> value.toString()
        is Double -> if (value.isFinite()) value.toString() else quote(value.toString())
        is Float -> if (value.isFinite()) value.toString() else quote(value.toString())
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> quote(k.toString()) + ":" + encode(v) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> quote(value.toString())
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
