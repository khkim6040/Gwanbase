package gwanbase.table

/**
 * 테이블 스키마. 컬럼 목록과 이름 기반 인덱스 조회를 제공한다.
 */
class Schema(val columns: List<Column>) {

    init {
        val names = columns.map { it.name }
        require(names.size == names.toSet().size) {
            "중복된 컬럼 이름이 존재한다: ${names.groupBy { it }.filter { it.value.size > 1 }.keys}"
        }
    }

    private val nameToIndex: Map<String, Int> =
        columns.mapIndexed { index, col -> col.name to index }.toMap()

    /** 컬럼 수 */
    val columnCount: Int get() = columns.size

    /**
     * 컬럼 이름으로 인덱스를 조회한다.
     * @throws IllegalArgumentException 존재하지 않는 이름
     */
    fun columnIndex(name: String): Int =
        requireNotNull(nameToIndex[name]) { "컬럼 '$name'이 스키마에 존재하지 않는다" }

    /**
     * 컬럼 이름으로 인덱스를 조회한다. 존재하지 않으면 null을 반환한다.
     */
    fun columnIndexOrNull(name: String): Int? = nameToIndex[name]

    /** 인덱스로 컬럼을 조회한다. */
    fun column(index: Int): Column = columns[index]
}
