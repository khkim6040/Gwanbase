package gwanbase.table

/**
 * 테이블 컬럼 정의.
 *
 * @param name 컬럼 이름
 * @param type 데이터 타입
 * @param maxLength VARCHAR 전용 최대 바이트 수 (다른 타입은 0)
 * @param nullable NULL 허용 여부 (기본 false)
 */
data class Column(
    val name: String,
    val type: DataType,
    val maxLength: Int = 0,
    val nullable: Boolean = false,
)
