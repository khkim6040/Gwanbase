package gwanbase.index

import gwanbase.storage.newPageBuffer
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * B+Tree 노드(리프/내부)의 페이지 내 레이아웃과 기본 접근자 테스트.
 *
 * 노드는 `ByteBuffer` 위에 18바이트 고정 헤더 + 정렬 슬롯 디렉터리 +
 * 역방향 레코드 영역 형태로 구성된다 (docs/specs/phase-1-kv-store.md 참조).
 */
class BPlusTreeNodeTest {

    @Test
    fun `빈 리프 노드 초기화 시 isLeaf는 true이고 keyCount는 0이다`() {
        val buffer = newPageBuffer()
        val node = BPlusTreeNode(buffer)
        node.initLeaf(parentPageId = -1)

        node.isLeaf.shouldBeTrue()
        node.keyCount shouldBe 0
    }

    @Test
    fun `빈 내부 노드 초기화 시 isLeaf는 false이고 keyCount는 0이다`() {
        val buffer = newPageBuffer()
        val node = BPlusTreeNode(buffer)
        node.initInternal(parentPageId = -1, rightmostChildPageId = -1)

        node.isLeaf.shouldBeFalse()
        node.keyCount shouldBe 0
    }

    @Test
    fun `리프 초기화 후 parentPageId와 nextLeafPageId가 올바르게 보존된다`() {
        val buffer = newPageBuffer()
        val node = BPlusTreeNode(buffer)
        node.initLeaf(parentPageId = 42)

        node.parentPageId shouldBe 42
        node.nextLeafPageId shouldBe BPlusTreeNode.INVALID_PAGE_ID
    }

    @Test
    fun `리프 nextLeafPageId는 쓰기 후 다시 읽을 수 있다`() {
        val buffer = newPageBuffer()
        val node = BPlusTreeNode(buffer)
        node.initLeaf(parentPageId = -1)

        node.nextLeafPageId = 123

        node.nextLeafPageId shouldBe 123
    }

    @Test
    fun `내부 노드 초기화 후 parentPageId와 rightmostChildPageId가 보존된다`() {
        val buffer = newPageBuffer()
        val node = BPlusTreeNode(buffer)
        node.initInternal(parentPageId = 7, rightmostChildPageId = 99)

        node.parentPageId shouldBe 7
        node.rightmostChildPageId shouldBe 99
    }

    @Test
    fun `내부 노드 rightmostChildPageId는 쓰기 후 다시 읽을 수 있다`() {
        val buffer = newPageBuffer()
        val node = BPlusTreeNode(buffer)
        node.initInternal(parentPageId = -1, rightmostChildPageId = -1)

        node.rightmostChildPageId = 55

        node.rightmostChildPageId shouldBe 55
    }

    @Test
    fun `빈 리프에서 findValue는 null을 반환한다`() {
        val node = BPlusTreeNode(newPageBuffer())
        node.initLeaf(parentPageId = -1)

        node.findValue("anything".toByteArray()).shouldBeNull()
    }

    @Test
    fun `리프에 한 건 삽입 후 findValue가 저장된 값을 반환한다`() {
        val node = BPlusTreeNode(newPageBuffer())
        node.initLeaf(parentPageId = -1)

        val inserted = node.insertLeafEntry("apple".toByteArray(), "red".toByteArray())

        inserted.shouldBeTrue()
        node.keyCount shouldBe 1
        node.findValue("apple".toByteArray()) shouldBe "red".toByteArray()
    }

    @Test
    fun `리프에 삽입하지 않은 키를 findValue로 조회하면 null을 반환한다`() {
        val node = BPlusTreeNode(newPageBuffer())
        node.initLeaf(parentPageId = -1)
        node.insertLeafEntry("apple".toByteArray(), "red".toByteArray())

        node.findValue("banana".toByteArray()).shouldBeNull()
    }

    @Test
    fun `리프에 여러 건을 임의 순서로 삽입 후 findValue가 모두 일치한다`() {
        val node = BPlusTreeNode(newPageBuffer())
        node.initLeaf(parentPageId = -1)

        val entries = listOf(
            "cherry" to "red",
            "apple" to "green",
            "banana" to "yellow",
            "date" to "brown",
        )
        entries.forEach { (k, v) ->
            node.insertLeafEntry(k.toByteArray(), v.toByteArray()).shouldBeTrue()
        }

        node.keyCount shouldBe 4
        entries.forEach { (k, v) ->
            node.findValue(k.toByteArray()) shouldBe v.toByteArray()
        }
    }

    @Test
    fun `리프에 임의 순서로 삽입해도 leafEntries는 키 오름차순으로 반환한다`() {
        val node = BPlusTreeNode(newPageBuffer())
        node.initLeaf(parentPageId = -1)

        listOf("cherry", "apple", "banana", "date").forEach {
            node.insertLeafEntry(it.toByteArray(), "v-$it".toByteArray())
        }

        val keys = node.leafEntries().map { String(it.first) }
        keys shouldBe listOf("apple", "banana", "cherry", "date")
    }

    @Test
    fun `같은 키로 insertLeafEntry를 두 번 호출하면 값이 최신으로 갱신되고 keyCount는 증가하지 않는다`() {
        val node = BPlusTreeNode(newPageBuffer())
        node.initLeaf(parentPageId = -1)

        node.insertLeafEntry("apple".toByteArray(), "red".toByteArray()).shouldBeTrue()
        node.insertLeafEntry("apple".toByteArray(), "green".toByteArray()).shouldBeTrue()

        node.keyCount shouldBe 1
        node.findValue("apple".toByteArray()) shouldBe "green".toByteArray()
    }
}
