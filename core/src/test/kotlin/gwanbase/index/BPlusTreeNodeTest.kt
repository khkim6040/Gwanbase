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
        node.initInternal(parentPageId = -1, leftmostChildPageId = -1)

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
    fun `내부 노드 초기화 후 parentPageId와 leftmostChildPageId가 보존된다`() {
        val buffer = newPageBuffer()
        val node = BPlusTreeNode(buffer)
        node.initInternal(parentPageId = 7, leftmostChildPageId = 99)

        node.parentPageId shouldBe 7
        node.leftmostChildPageId shouldBe 99
    }

    @Test
    fun `내부 노드 leftmostChildPageId는 쓰기 후 다시 읽을 수 있다`() {
        val buffer = newPageBuffer()
        val node = BPlusTreeNode(buffer)
        node.initInternal(parentPageId = -1, leftmostChildPageId = -1)

        node.leftmostChildPageId = 55

        node.leftmostChildPageId shouldBe 55
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

    @Test
    fun `빈 내부 노드에서 findChild는 leftmostChildPageId를 반환한다`() {
        val node = BPlusTreeNode(newPageBuffer())
        node.initInternal(parentPageId = -1, leftmostChildPageId = 999)

        node.findChild("any".toByteArray()) shouldBe 999
    }

    @Test
    fun `splitLeaf는 절반을 새 노드로 옮기고 오른쪽 노드의 첫 키를 promote 키로 반환한다`() {
        val left = BPlusTreeNode(newPageBuffer())
        left.initLeaf(parentPageId = 50)
        left.nextLeafPageId = 99 // 기존 오른쪽 이웃
        val right = BPlusTreeNode(newPageBuffer())
        right.initLeaf(parentPageId = 50)

        listOf("a", "b", "c", "d", "e", "f").forEach {
            left.insertLeafEntry(it.toByteArray(), "v-$it".toByteArray())
        }

        val promoteKey = left.splitLeaf(newRightNode = right, newRightPageId = 7)

        // 6건이 3/3으로 분할된다 (대략 절반)
        left.leafEntries().map { String(it.first) } shouldBe listOf("a", "b", "c")
        right.leafEntries().map { String(it.first) } shouldBe listOf("d", "e", "f")
        String(promoteKey) shouldBe "d"
        // 리프 체인: left → right → (기존 오른쪽 이웃 99)
        left.nextLeafPageId shouldBe 7
        right.nextLeafPageId shouldBe 99
        right.parentPageId shouldBe 50
    }

    @Test
    fun `splitInternal은 중간 키를 promote하고 그 자식을 오른쪽 leftmost로 이관한다`() {
        val left = BPlusTreeNode(newPageBuffer())
        left.initInternal(parentPageId = 1, leftmostChildPageId = 9999)
        val right = BPlusTreeNode(newPageBuffer())
        right.initInternal(parentPageId = 1, leftmostChildPageId = -1)

        // 삽입: (banana,10),(date,20),(fig,30),(mango,40),(pear,50)
        //   leftmost=9999
        //   slot[0..4] = [(banana,10),(date,20),(fig,30),(mango,40),(pear,50)]
        //   자식 포인터(왼→오): [9999, 10, 20, 30, 40, 50]
        listOf("banana" to 10, "date" to 20, "fig" to 30, "mango" to 40, "pear" to 50)
            .forEach { (k, c) -> left.insertInternalEntry(k.toByteArray(), c).shouldBeTrue() }

        val promoteKey = left.splitInternal(newRightNode = right)

        // mid=2: slot[2].key="fig"가 promote, slot[2].child=30은 오른쪽 leftmost가 된다.
        //   left:  leftmost=9999, slots=[(banana,10),(date,20)]
        //   right: leftmost=30,   slots=[(mango,40),(pear,50)]
        String(promoteKey) shouldBe "fig"

        left.keyCount shouldBe 2
        left.findChild("apple".toByteArray()) shouldBe 9999      // < banana → leftmost
        left.findChild("banana".toByteArray()) shouldBe 10       // [banana, date) → slot[0]
        left.findChild("cherry".toByteArray()) shouldBe 10
        left.findChild("date".toByteArray()) shouldBe 20         // [date, ...) → slot[1]
        left.findChild("elderberry".toByteArray()) shouldBe 20

        right.keyCount shouldBe 2
        right.findChild("fig".toByteArray()) shouldBe 30         // < mango → leftmost=30
        right.findChild("lychee".toByteArray()) shouldBe 30
        right.findChild("mango".toByteArray()) shouldBe 40       // [mango, pear) → slot[0]
        right.findChild("orange".toByteArray()) shouldBe 40
        right.findChild("pear".toByteArray()) shouldBe 50        // [pear, ...) → slot[1]
        right.findChild("quince".toByteArray()) shouldBe 50
    }

    @Test
    fun `내부 노드에 (key, child) 항목을 삽입한 후 findChild가 범위에 맞는 자식을 반환한다`() {
        val node = BPlusTreeNode(newPageBuffer())
        node.initInternal(parentPageId = -1, leftmostChildPageId = 999)

        // 규약: slot[i].child는 key ≥ slot[i].key인 서브트리.
        //   ( -inf, banana) → 999 (leftmost)
        //   [banana, date)  → 10
        //   [date,  fig)    → 20
        //   [fig,  +inf)    → 30
        // 삽입 순서는 정렬과 다르게 한다.
        node.insertInternalEntry("date".toByteArray(), 20).shouldBeTrue()
        node.insertInternalEntry("banana".toByteArray(), 10).shouldBeTrue()
        node.insertInternalEntry("fig".toByteArray(), 30).shouldBeTrue()

        node.keyCount shouldBe 3
        node.findChild("apple".toByteArray()) shouldBe 999
        node.findChild("banana".toByteArray()) shouldBe 10
        node.findChild("cherry".toByteArray()) shouldBe 10
        node.findChild("date".toByteArray()) shouldBe 20
        node.findChild("elderberry".toByteArray()) shouldBe 20
        node.findChild("fig".toByteArray()) shouldBe 30
        node.findChild("grape".toByteArray()) shouldBe 30
    }
}
