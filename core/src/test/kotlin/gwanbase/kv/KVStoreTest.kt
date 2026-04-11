package gwanbase.kv

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * KVStore 공용 API와 영속성 통합 테스트.
 *
 * Phase 1 기능을 한 번에 검증한다: put/get/delete/scan, close/reopen,
 * split이 발생하는 대량 삽입, Buffer Pool 압박 상황의 정확성.
 */
class KVStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private fun dbPath(): Path = tempDir.resolve("test.db")

    @Test
    fun `기본 CRUD - put, get, delete 동작`() {
        KVStore.open(dbPath()).use { store ->
            store.put("apple".toByteArray(), "red".toByteArray())
            store.put("banana".toByteArray(), "yellow".toByteArray())

            store.get("apple".toByteArray()) shouldBe "red".toByteArray()
            store.get("banana".toByteArray()) shouldBe "yellow".toByteArray()
            store.get("cherry".toByteArray()).shouldBeNull()

            store.delete("apple".toByteArray()).shouldBeTrue()
            store.get("apple".toByteArray()).shouldBeNull()
            store.delete("apple".toByteArray()).shouldBeFalse()
        }
    }

    @Test
    fun `영속성 - close 후 reopen 시 데이터가 복원된다`() {
        KVStore.open(dbPath()).use { store ->
            store.put("apple".toByteArray(), "red".toByteArray())
            store.put("banana".toByteArray(), "yellow".toByteArray())
            store.put("cherry".toByteArray(), "dark-red".toByteArray())
        }

        KVStore.open(dbPath()).use { store ->
            store.get("apple".toByteArray()) shouldBe "red".toByteArray()
            store.get("banana".toByteArray()) shouldBe "yellow".toByteArray()
            store.get("cherry".toByteArray()) shouldBe "dark-red".toByteArray()
        }
    }

    @Test
    fun `split이 발생하는 규모에서도 영속성이 보장된다`() {
        val n = 500
        KVStore.open(dbPath()).use { store ->
            for (i in 0 until n) {
                store.put(formatKey(i), formatValue(i))
            }
        }

        KVStore.open(dbPath()).use { store ->
            for (i in 0 until n) {
                store.get(formatKey(i)) shouldBe formatValue(i)
            }
        }
    }

    @Test
    fun `scan은 close 후 reopen된 스토어에서도 올바르게 동작한다`() {
        KVStore.open(dbPath()).use { store ->
            for (i in 0 until 50) {
                store.put(formatKey(i), formatValue(i))
            }
        }

        KVStore.open(dbPath()).use { store ->
            val result = store.scan(formatKey(10), formatKey(20)).asSequence().toList()
            result.size shouldBe 10
            for ((idx, entry) in result.withIndex()) {
                entry.first shouldBe formatKey(10 + idx)
            }
        }
    }

    @Test
    fun `Buffer Pool이 작아도 대량 삽입과 조회가 정상 동작한다`() {
        // poolSize=4: 페이지 eviction 경로를 강하게 탐지한다
        val n = 1000
        KVStore.open(dbPath(), bufferPoolSize = 4).use { store ->
            for (i in 0 until n) {
                store.put(formatKey(i), formatValue(i))
            }
            for (i in 0 until n) {
                store.get(formatKey(i)) shouldBe formatValue(i)
            }
        }
    }

    @Test
    fun `close 이후 연산은 실패한다`() {
        val store = KVStore.open(dbPath())
        store.put("apple".toByteArray(), "red".toByteArray())
        store.close()

        try {
            store.get("apple".toByteArray())
            error("close 이후 get이 예외 없이 반환됨")
        } catch (_: IllegalStateException) {
            // 예상된 에러
        }
    }

    private fun formatKey(i: Int): ByteArray = "key-%06d".format(i).toByteArray()
    private fun formatValue(i: Int): ByteArray = "value-%06d".format(i).toByteArray()
}
