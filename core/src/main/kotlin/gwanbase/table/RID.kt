package gwanbase.table

/**
 * 튜플의 물리 위치를 가리키는 Record ID.
 *
 * HeapFile 내에서 [pageId] 페이지의 [slotId] 슬롯에 위치한 레코드를 식별한다.
 */
data class RID(val pageId: Int, val slotId: Int)
