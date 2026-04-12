package gwanbase.index

import gwanbase.storage.BufferPoolManager

/**
 * 디스크 기반 B+Tree 인덱스.
 *
 * 모든 페이지 접근은 [BufferPoolManager]를 통해 이루어진다.
 * split과 root promotion, 다단계 트리 탐색을 지원한다. delete(lazy)와
 * 범위 scan은 후속 TDD 사이클에서 추가된다.
 *
 * 동시성은 Phase 1 범위 밖이다 (단일 스레드 가정).
 *
 * @param bpm 페이지 I/O를 담당하는 버퍼 풀 매니저
 * @param initialRootPageId 루트 노드가 위치한 페이지 ID
 */
class BPlusTree internal constructor(
    private val bpm: BufferPoolManager,
    initialRootPageId: Int,
) {

    /** 현재 루트 페이지 ID. 루트 split 발생 시 갱신된다. */
    var rootPageId: Int = initialRootPageId
        private set

    /**
     * 주어진 [key]에 해당하는 값을 반환한다.
     * 키가 존재하지 않으면 null을 반환한다.
     */
    fun search(key: ByteArray): ByteArray? {
        var pageId = rootPageId
        while (true) {
            val page = bpm.fetchPage(pageId) ?: error("page not found: $pageId")
            val nextPageId: Int
            try {
                val node = BPlusTreeNode(page.data)
                if (node.isLeaf) {
                    return node.findValue(key)
                }
                nextPageId = node.findChild(key)
            } finally {
                bpm.unpinPage(pageId, isDirty = false)
            }
            pageId = nextPageId
        }
    }

    /**
     * [key]에 [value]를 저장한다. 이미 같은 키가 존재하면 값을 갱신한다.
     *
     * 삽입 경로에서 leaf가 가득 차면 split이 발생하고 promote 키가 부모로
     * 전파된다. 부모도 가득 차면 재귀적으로 split이 일어나며, 루트까지 도달
     * 하면 새 내부 노드 루트가 생성된다.
     */
    fun insert(key: ByteArray, value: ByteArray) {
        val path = findLeafPath(key)
        val leafPageId = path.removeAt(path.size - 1)

        val leafPage = bpm.fetchPage(leafPageId) ?: error("leaf not found: $leafPageId")
        var leafDirty = false
        try {
            val leaf = BPlusTreeNode(leafPage.data)
            check(leaf.isLeaf) { "탐색이 리프가 아닌 노드에서 종료되었다: $leafPageId" }

            if (leaf.insertLeafEntry(key, value)) {
                leafDirty = true
                return
            }

            // 리프 가득 — split 후 새 엔트리를 올바른 절반에 삽입
            val (newLeafId, leafPromoteKey) = splitLeafAndInsertNewEntry(leaf, key, value)
            leafDirty = true

            // 상위로 전파
            propagateSplit(
                path = path,
                leftChildId = leafPageId,
                promoteKey = leafPromoteKey,
                rightChildId = newLeafId,
            )
        } finally {
            bpm.unpinPage(leafPageId, isDirty = leafDirty)
        }
    }

    /**
     * [startKey] 이상 [endKey] 미만 범위의 (key, value) 쌍을 키 오름차순으로 반환한다.
     *
     * 구현은 leaf 체인을 따라가며 조건을 만족하는 엔트리만 lazy 하게 내보낸다.
     * 각 leaf 단위로는 모든 엔트리를 힙 메모리에 복사해 가면서 페이지를
     * 즉시 unpin 한다 (scan 도중 긴 pin 유지 방지).
     */
    fun scan(startKey: ByteArray, endKey: ByteArray): Iterator<Pair<ByteArray, ByteArray>> {
        return sequence {
            val path = findLeafPath(startKey)
            var currentLeafId = path.last()

            while (currentLeafId != BPlusTreeNode.INVALID_PAGE_ID) {
                val page = bpm.fetchPage(currentLeafId) ?: error("leaf not found: $currentLeafId")
                val entries: List<Pair<ByteArray, ByteArray>>
                val nextLeaf: Int
                try {
                    val leaf = BPlusTreeNode(page.data)
                    check(leaf.isLeaf) { "scan 중 내부 노드를 만났다: $currentLeafId" }
                    entries = leaf.leafEntries()
                    nextLeaf = leaf.nextLeafPageId
                } finally {
                    bpm.unpinPage(currentLeafId, isDirty = false)
                }

                for ((k, v) in entries) {
                    if (compareUnsigned(k, startKey) < 0) continue
                    if (compareUnsigned(k, endKey) >= 0) return@sequence
                    yield(k to v)
                }
                currentLeafId = nextLeaf
            }
        }.iterator()
    }

    /**
     * [key]를 제거한다 (Phase 1 lazy delete).
     *
     * 해당 리프에서 슬롯 엔트리만 제거하며 underflow/merge/rebalance는
     * 구현하지 않는다. 제거된 레코드는 dead space가 된다.
     *
     * @return 키가 존재하여 제거되었으면 true, 없었으면 false
     */
    fun delete(key: ByteArray): Boolean {
        val path = findLeafPath(key)
        val leafPageId = path.last()

        val page = bpm.fetchPage(leafPageId) ?: error("leaf not found: $leafPageId")
        var dirty = false
        try {
            val leaf = BPlusTreeNode(page.data)
            check(leaf.isLeaf) { "탐색 결과가 리프가 아니다: $leafPageId" }
            val removed = leaf.deleteLeafEntry(key)
            dirty = removed
            return removed
        } finally {
            bpm.unpinPage(leafPageId, isDirty = dirty)
        }
    }

    /**
     * 루트에서 리프까지의 페이지 ID 경로를 반환한다.
     * 리스트의 마지막 원소가 리프이고, 그 이전은 조상 내부 노드들이다.
     * 탐색 과정에서 페이지는 즉시 unpin한다.
     */
    private fun findLeafPath(key: ByteArray): MutableList<Int> {
        val path = mutableListOf<Int>()
        var currentId = rootPageId
        while (true) {
            path.add(currentId)
            val page = bpm.fetchPage(currentId) ?: error("page not found: $currentId")
            val isLeaf: Boolean
            val nextId: Int
            try {
                val node = BPlusTreeNode(page.data)
                isLeaf = node.isLeaf
                nextId = if (isLeaf) BPlusTreeNode.INVALID_PAGE_ID else node.findChild(key)
            } finally {
                bpm.unpinPage(currentId, isDirty = false)
            }
            if (isLeaf) return path
            currentId = nextId
        }
    }

    /**
     * 가득 찬 리프 [leaf]를 분할한 뒤 새 엔트리 ([insertKey], [insertValue])를
     * 적절한 절반에 삽입한다.
     *
     * @return (새 오른쪽 리프 pageId, 부모로 promote할 key)
     */
    private fun splitLeafAndInsertNewEntry(
        leaf: BPlusTreeNode,
        insertKey: ByteArray,
        insertValue: ByteArray,
    ): Pair<Int, ByteArray> {
        val newLeafPage = bpm.newPage() ?: error("신규 리프 페이지 할당 실패")
        val newLeafPageId = newLeafPage.pageId
        try {
            val newLeaf = BPlusTreeNode(newLeafPage.data)
            newLeaf.initLeaf(parentPageId = leaf.parentPageId)

            val promoteKey = leaf.splitLeaf(newRightNode = newLeaf, newRightPageId = newLeafPageId)

            val inserted = if (compareUnsigned(insertKey, promoteKey) < 0) {
                leaf.insertLeafEntry(insertKey, insertValue)
            } else {
                newLeaf.insertLeafEntry(insertKey, insertValue)
            }
            check(inserted) { "split 직후에도 해당 절반에 엔트리를 넣을 공간이 없다" }

            return newLeafPageId to promoteKey
        } finally {
            bpm.unpinPage(newLeafPageId, isDirty = true)
        }
    }

    /**
     * 자식 노드가 분할된 결과를 부모로 전파한다.
     *
     * @param path 리프 쪽에서 루트 쪽으로 거슬러 올라갈 내부 조상 페이지 ID 스택.
     *   마지막 원소가 가장 가까운 부모, 첫 원소가 루트.
     * @param leftChildId 분할 후 왼쪽 자식(기존 페이지 ID)
     * @param promoteKey 부모에 삽입할 separator 키
     * @param rightChildId 새로 할당된 오른쪽 자식 페이지 ID
     */
    private fun propagateSplit(
        path: MutableList<Int>,
        leftChildId: Int,
        promoteKey: ByteArray,
        rightChildId: Int,
    ) {
        var curLeft = leftChildId
        var curKey = promoteKey
        var curRight = rightChildId

        while (path.isNotEmpty()) {
            val parentPageId = path.removeAt(path.size - 1)
            val parentPage = bpm.fetchPage(parentPageId) ?: error("parent not found: $parentPageId")
            var parentDirty = false
            try {
                val parent = BPlusTreeNode(parentPage.data)

                if (parent.insertInternalEntry(curKey, curRight)) {
                    parentDirty = true
                    return
                }

                // 부모 가득 — 부모 split (새 엔트리 포함)
                val (newParentRightId, newPromoteKey) =
                    splitInternalWithNewEntry(parent, curKey, curRight)
                parentDirty = true

                curLeft = parentPageId
                curKey = newPromoteKey
                curRight = newParentRightId
            } finally {
                bpm.unpinPage(parentPageId, isDirty = parentDirty)
            }
        }

        // path 소진 → 루트 split: 새 루트 내부 노드 생성
        createNewRoot(curLeft, curKey, curRight)
    }

    /**
     * 가득 찬 내부 노드 [parent]를 새 엔트리 ([newKey], [newChildId])와 함께 분할한다.
     *
     * 구현은 "수집 → 재초기화 → 재분배" 방식이다. 기존 엔트리와 새 엔트리를
     * 정렬 순서대로 합친 리스트를 만든 뒤, 중간 키를 promote하고 왼쪽/오른쪽에
     * 각각 재삽입한다.
     *
     * @return (새 오른쪽 내부 노드 pageId, 부모로 promote할 key)
     */
    private fun splitInternalWithNewEntry(
        parent: BPlusTreeNode,
        newKey: ByteArray,
        newChildId: Int,
    ): Pair<Int, ByteArray> {
        val merged = mergeSorted(parent.internalEntries(), newKey, newChildId)

        val mid = merged.size / 2
        val leftPairs = merged.subList(0, mid)
        val promoteKey = merged[mid].first
        val midChild = merged[mid].second
        val rightPairs = merged.subList(mid + 1, merged.size)

        val origLeftmost = parent.leftmostChildPageId
        val origParent = parent.parentPageId

        // 왼쪽(기존) 노드 재초기화 + 앞쪽 절반
        parent.initInternal(parentPageId = origParent, leftmostChildPageId = origLeftmost)
        for ((k, c) in leftPairs) {
            check(parent.insertInternalEntry(k, c)) { "내부 split 후 왼쪽에 재삽입 실패" }
        }

        // 오른쪽 내부 노드 할당 + 뒤쪽 절반 (leftmost는 promote된 키의 자식)
        val newRightPage = bpm.newPage() ?: error("신규 내부 노드 페이지 할당 실패")
        val newRightPageId = newRightPage.pageId
        try {
            val newRight = BPlusTreeNode(newRightPage.data)
            newRight.initInternal(parentPageId = origParent, leftmostChildPageId = midChild)
            for ((k, c) in rightPairs) {
                check(newRight.insertInternalEntry(k, c)) { "내부 split 후 오른쪽에 재삽입 실패" }
            }
        } finally {
            bpm.unpinPage(newRightPageId, isDirty = true)
        }

        return newRightPageId to promoteKey
    }

    /**
     * 기존 sorted 엔트리 목록에 새 엔트리를 정렬 순서대로 끼워 넣은 리스트를 만든다.
     */
    private fun mergeSorted(
        existing: List<Pair<ByteArray, Int>>,
        newKey: ByteArray,
        newChildId: Int,
    ): List<Pair<ByteArray, Int>> {
        val result = ArrayList<Pair<ByteArray, Int>>(existing.size + 1)
        var inserted = false
        for (entry in existing) {
            if (!inserted && compareUnsigned(newKey, entry.first) < 0) {
                result.add(newKey to newChildId)
                inserted = true
            }
            result.add(entry)
        }
        if (!inserted) result.add(newKey to newChildId)
        return result
    }

    /**
     * 루트 split 결과로 새 내부 노드 루트를 생성한다.
     * leftmostChild에 기존 왼쪽 자식, slot[0]에 (promoteKey, 오른쪽 자식)을 넣는다.
     */
    private fun createNewRoot(leftChildId: Int, promoteKey: ByteArray, rightChildId: Int) {
        val newRootPage = bpm.newPage() ?: error("신규 루트 페이지 할당 실패")
        val newRootPageId = newRootPage.pageId
        try {
            val newRoot = BPlusTreeNode(newRootPage.data)
            newRoot.initInternal(
                parentPageId = BPlusTreeNode.INVALID_PAGE_ID,
                leftmostChildPageId = leftChildId,
            )
            check(newRoot.insertInternalEntry(promoteKey, rightChildId)) {
                "새 루트 노드에 초기 엔트리 삽입 실패"
            }
        } finally {
            bpm.unpinPage(newRootPageId, isDirty = true)
        }
        rootPageId = newRootPageId
    }

    /** unsigned lexicographic byte 비교 */
    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }

    companion object {
        /**
         * 새 빈 B+Tree를 생성한다.
         * 루트 페이지를 할당하고 빈 리프로 초기화한다.
         */
        fun createNew(bpm: BufferPoolManager): BPlusTree {
            val rootPage = bpm.newPage() ?: error("루트 페이지 할당 실패")
            val rootPageId = rootPage.pageId
            BPlusTreeNode(rootPage.data).initLeaf(parentPageId = BPlusTreeNode.INVALID_PAGE_ID)
            bpm.unpinPage(rootPageId, isDirty = true)
            return BPlusTree(bpm, rootPageId)
        }
    }
}
