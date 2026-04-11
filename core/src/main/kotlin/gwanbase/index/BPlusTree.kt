package gwanbase.index

import gwanbase.storage.BufferPoolManager

/**
 * 디스크 기반 B+Tree 인덱스.
 *
 * 모든 페이지 접근은 [BufferPoolManager]를 통해 이루어진다.
 * 현재 구현은 단일 리프(= 루트도 리프인) 상태만 지원한다. split과
 * 다단계 트리, delete, scan은 후속 TDD 사이클에서 추가된다.
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
            var nextPageId = BPlusTreeNode.INVALID_PAGE_ID
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
     * 현재 구현은 루트가 리프인 단일 노드 트리만 지원한다.
     * 해당 리프에 여유 공간이 없으면 IllegalStateException을 던진다.
     */
    fun insert(key: ByteArray, value: ByteArray) {
        val page = bpm.fetchPage(rootPageId) ?: error("root page not found: $rootPageId")
        try {
            val node = BPlusTreeNode(page.data)
            check(node.isLeaf) { "다단계 트리는 아직 지원되지 않는다" }
            val success = node.insertLeafEntry(key, value)
            check(success) { "리프 공간 부족 - split은 아직 구현되지 않았다" }
        } finally {
            bpm.unpinPage(rootPageId, isDirty = true)
        }
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
