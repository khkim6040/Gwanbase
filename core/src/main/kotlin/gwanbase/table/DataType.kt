package gwanbase.table

/**
 * 지원하는 데이터 타입.
 *
 * [fixedSize]가 null이면 가변 크기 타입이다 (예: VARCHAR).
 */
enum class DataType(val fixedSize: Int?) {
    BOOLEAN(1),
    INT32(4),
    INT64(8),
    FLOAT64(8),
    TIMESTAMP(8),
    VARCHAR(null);

    /** 고정 크기 타입 여부 */
    val isFixedSize: Boolean get() = fixedSize != null
}
