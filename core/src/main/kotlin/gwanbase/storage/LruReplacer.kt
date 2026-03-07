package gwanbase.storage

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * LRU 교체 정책 구현.
 *
 * Buffer Pool에서 eviction 대상을 선택할 때 사용한다.
 * unpin된 페이지만 이 replacer에 들어오며, 가장 오래 사용되지 않은 프레임을 반환한다.
 */
class LruReplacer(private val capacity: Int) {

    // 앞(head)이 가장 오래된 것, 뒤(tail)이 가장 최근
    private val list = ConcurrentLinkedDeque<Int>()
    private val inList = HashSet<Int>()

    @Synchronized
    fun victim(): Int? {
        if (list.isEmpty()) return null
        val frameId = list.pollFirst()
        inList.remove(frameId)
        return frameId
    }

    /** 해당 프레임이 pin되었으므로 eviction 후보에서 제거 */
    @Synchronized
    fun pin(frameId: Int) {
        if (inList.remove(frameId)) {
            list.remove(frameId)
        }
    }

    /** 해당 프레임이 unpin되었으므로 eviction 후보에 추가 */
    @Synchronized
    fun unpin(frameId: Int) {
        if (inList.contains(frameId)) return
        if (inList.size >= capacity) return
        list.addLast(frameId)
        inList.add(frameId)
    }

    val size: Int
        @Synchronized get() = inList.size
}
