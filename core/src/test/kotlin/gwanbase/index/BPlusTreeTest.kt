package gwanbase.index

import gwanbase.storage.BufferPoolManager
import gwanbase.storage.DiskManager
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

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
        bpm = BufferPoolManager(diskManager, poolSize = 16)
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
}
