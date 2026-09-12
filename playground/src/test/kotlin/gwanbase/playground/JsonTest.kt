package gwanbase.playground


import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JsonTest {

    @Test
    fun `기본 타입은 JSON 리터럴로 인코딩된다`() {
        Json.encode(null) shouldBe "null"
        Json.encode(true) shouldBe "true"
        Json.encode(42) shouldBe "42"
        Json.encode(3.5) shouldBe "3.5"
        Json.encode(10_000_000_000L) shouldBe "10000000000"
    }

    @Test
    fun `문자열의 따옴표 역슬래시 개행 제어문자를 escape한다`() {
        Json.encode("a\"b\\c\nd\te") shouldBe "\"a\\\"b\\\\c\\nd\\te\\u0001\""
    }

    @Test
    fun `Map은 객체로 List는 배열로 중첩 인코딩된다`() {
        val value = mapOf("columns" to listOf("id", "name"), "rows" to listOf(listOf(1, "Alice"), listOf(2, null)))
        Json.encode(value) shouldBe """{"columns":["id","name"],"rows":[[1,"Alice"],[2,null]]}"""
    }

    @Test
    fun `알 수 없는 타입은 toString 문자열로 인코딩된다`() {
        Json.encode(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")) shouldBe
            "\"00000000-0000-0000-0000-000000000001\""
    }
}
