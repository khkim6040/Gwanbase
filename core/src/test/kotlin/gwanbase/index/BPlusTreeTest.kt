package gwanbase.index

import gwanbase.storage.BufferPoolManager
import gwanbase.storage.DiskManager
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 트리 레벨 B+Tree 동작 테스트.
 *
 * 각 테스트는 `@TempDir`에서 새 파일을 만들고, BufferPoolManager 위에
 * `BPlusTree`를 생성한다. 한 리프에 들어가는 규모부터 시작해 split이
 * 필요한 규모로 단계적으로 확장한다.
 */
class BPlusTreeTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var diskManager: DiskManager
    private lateinit var bpm: BufferPoolManager
    private lateinit var tree: BPlusTree

    @BeforeEach
    fun setUp() {
        diskManager = DiskManager(tempDir.resolve("test.db"))
        bpm = BufferPoolManager(diskManager, poolSize = 64)
        tree = BPlusTree.createNew(bpm)
    }

    @AfterEach
    fun tearDown() {
        diskManager.close()
    }

    @Test
    fun `빈 트리에서 search는 null을 반환한다`() {
        tree.search("anything".toByteArray()).shouldBeNull()
    }

    @Test
    fun `한 건 삽입 후 같은 키로 search하면 저장된 값을 반환한다`() {
        tree.insert("apple".toByteArray(), "red".toByteArray())

        tree.search("apple".toByteArray()) shouldBe "red".toByteArray()
        tree.search("banana".toByteArray()).shouldBeNull()
    }

    @Test
    fun `단일 리프 범위 내에 여러 건 삽입하면 모두 조회 가능하다`() {
        val entries = listOf(
            "cherry" to "red",
            "apple" to "green",
            "banana" to "yellow",
            "date" to "brown",
            "elderberry" to "purple",
        )
        for ((k, v) in entries) {
            tree.insert(k.toByteArray(), v.toByteArray())
        }

        for ((k, v) in entries) {
            tree.search(k.toByteArray()) shouldBe v.toByteArray()
        }
    }

    @Test
    fun `같은 키로 두 번 insert하면 값이 최신으로 갱신된다`() {
        tree.insert("apple".toByteArray(), "red".toByteArray())
        tree.insert("apple".toByteArray(), "green".toByteArray())

        tree.search("apple".toByteArray()) shouldBe "green".toByteArray()
    }

    @Test
    fun `리프 용량을 초과할 만큼 순차 삽입해도 모든 키가 조회된다`() {
        // 한 리프(4KB)의 대략적인 용량을 넘도록 충분히 많이 넣는다.
        // 이 과정에서 leaf split과 루트 split(leaf → internal 승격)이 발생해야 한다.
        val n = 500
        for (i in 0 until n) {
            val k = formatKey(i)
            val v = formatValue(i)
            tree.insert(k, v)
        }

        for (i in 0 until n) {
            tree.search(formatKey(i)) shouldBe formatValue(i)
        }
        tree.search("absent-key".toByteArray()).shouldBeNull()
    }

    @Test
    fun `루트 split이 일어나도 rootPageId는 변하지 않는다`() {
        val initialRoot = tree.rootPageId
        val n = 500
        for (i in 0 until n) {
            tree.insert(formatKey(i), formatValue(i))
        }

        tree.rootPageId shouldBe initialRoot
        // 외부(Catalog 등)가 기억한 rootPageId로 다시 열어도 모든 키를 찾아야 한다
        val reopened = BPlusTree(bpm, initialRoot)
        for (i in 0 until n) {
            reopened.search(formatKey(i)) shouldBe formatValue(i)
        }
    }

    @Test
    fun `임의 순서로 대량 삽입해도 모든 키가 조회되고 정렬 불변식이 유지된다`() {
        val n = 2000
        val indices = (0 until n).toMutableList()
        indices.shuffle(kotlin.random.Random(seed = 42))

        for (i in indices) {
            tree.insert(formatKey(i), formatValue(i))
        }
        for (i in 0 until n) {
            tree.search(formatKey(i)) shouldBe formatValue(i)
        }
    }

    @Test
    fun `delete 후 같은 키를 search하면 null을 반환한다`() {
        tree.insert("apple".toByteArray(), "red".toByteArray())
        tree.insert("banana".toByteArray(), "yellow".toByteArray())

        tree.delete("apple".toByteArray()).shouldBeTrue()

        tree.search("apple".toByteArray()).shouldBeNull()
        tree.search("banana".toByteArray()) shouldBe "yellow".toByteArray()
    }

    @Test
    fun `존재하지 않는 키를 delete하면 false를 반환한다`() {
        tree.insert("apple".toByteArray(), "red".toByteArray())

        tree.delete("nonexistent".toByteArray()).shouldBeFalse()
        tree.search("apple".toByteArray()) shouldBe "red".toByteArray()
    }

    @Test
    fun `delete된 키를 다시 insert하면 새 값이 조회된다`() {
        tree.insert("apple".toByteArray(), "red".toByteArray())
        tree.delete("apple".toByteArray())
        tree.insert("apple".toByteArray(), "green".toByteArray())

        tree.search("apple".toByteArray()) shouldBe "green".toByteArray()
    }

    @Test
    fun `split이 발생한 트리에서도 delete가 올바르게 동작한다`() {
        // split이 확실히 일어나도록 500건 삽입
        val n = 500
        for (i in 0 until n) {
            tree.insert(formatKey(i), formatValue(i))
        }
        // 짝수 인덱스 키를 모두 삭제
        for (i in 0 until n step 2) {
            tree.delete(formatKey(i)).shouldBeTrue()
        }
        // 짝수는 null, 홀수는 여전히 조회 가능
        for (i in 0 until n) {
            val result = tree.search(formatKey(i))
            if (i % 2 == 0) {
                result.shouldBeNull()
            } else {
                result shouldBe formatValue(i)
            }
        }
    }

    @Test
    fun `scan은 start 이상 end 미만 범위를 키 오름차순으로 반환한다`() {
        for (i in 0 until 20) {
            tree.insert(formatKey(i), formatValue(i))
        }

        val result = tree.scan(formatKey(5), formatKey(15)).asSequence().toList()

        result.size shouldBe 10
        for ((idx, entry) in result.withIndex()) {
            entry.first shouldBe formatKey(5 + idx)
            entry.second shouldBe formatValue(5 + idx)
        }
    }

    @Test
    fun `scan은 리프 경계를 넘어 여러 리프에 걸친 범위도 반환한다`() {
        val n = 1000
        for (i in 0 until n) {
            tree.insert(formatKey(i), formatValue(i))
        }

        val result = tree.scan(formatKey(100), formatKey(250)).asSequence().toList()

        result.size shouldBe 150
        for ((idx, entry) in result.withIndex()) {
            entry.first shouldBe formatKey(100 + idx)
        }
    }

    @Test
    fun `scan의 start가 모든 키보다 작으면 전체에서 end 미만을 반환한다`() {
        for (i in 0 until 10) {
            tree.insert(formatKey(i), formatValue(i))
        }

        val result = tree.scan("aaaa".toByteArray(), formatKey(5)).asSequence().toList()

        result.size shouldBe 5
        result.first().first shouldBe formatKey(0)
        result.last().first shouldBe formatKey(4)
    }

    @Test
    fun `scan의 start가 모든 키보다 크면 빈 결과를 반환한다`() {
        for (i in 0 until 10) {
            tree.insert(formatKey(i), formatValue(i))
        }

        val result = tree.scan("zzzz".toByteArray(), "zzzzz".toByteArray()).asSequence().toList()

        result shouldBe emptyList()
    }

    @Test
    fun `scan의 end가 null이면 start 이상 전체를 리프 경계를 넘어 반환한다`() {
        for (i in 0 until 300) {
            tree.insert(formatKey(i), formatValue(i))
        }

        val result = tree.scan(formatKey(250), null).asSequence().toList()

        result.size shouldBe 50
        result.first().first shouldBe formatKey(250)
        result.last().first shouldBe formatKey(299)
    }

    @Test
    fun `scan의 start가 빈 배열이고 end가 null이면 전체를 반환한다`() {
        for (i in 0 until 20) {
            tree.insert(formatKey(i), formatValue(i))
        }

        val result = tree.scan(ByteArray(0), null).asSequence().toList()

        result.size shouldBe 20
    }

    @Test
    fun `여러 스레드가 동시에 삽입해도 모든 키가 조회되고 scan 정렬 불변식이 유지된다`() {
        val threads = 4
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(threads) { t ->
            pool.submit {
                try {
                    for (i in t until threads * perThread step threads) {
                        tree.insert(formatKey(i), formatValue(i))
                    }
                } catch (e: Throwable) {
                    failures.add(e)
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS).shouldBeTrue()
        failures.shouldBeEmpty()

        val total = threads * perThread
        for (i in 0 until total) {
            tree.search(formatKey(i)) shouldBe formatValue(i)
        }
        val keys = tree.scan(ByteArray(0), null).asSequence().map { String(it.first) }.toList()
        keys.size shouldBe total
        keys shouldBe keys.sorted()
    }

    private fun formatKey(i: Int): ByteArray = "key-%06d".format(i).toByteArray()
    private fun formatValue(i: Int): ByteArray = "value-%06d".format(i).toByteArray()
}
