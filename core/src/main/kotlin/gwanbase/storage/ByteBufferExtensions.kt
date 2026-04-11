package gwanbase.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 바이트 조작 유틸리티 확장 함수 모음.
 *
 * Kotlin에서 JVM ByteBuffer를 다룰 때 verbose한 부분을 줄여준다.
 */

/** 가변 길이 바이트 배열을 [length(4bytes) | data] 형태로 기록 */
fun ByteBuffer.putLengthPrefixed(data: ByteArray): ByteBuffer {
    putInt(data.size)
    put(data)
    return this
}

/** [length(4bytes) | data] 형태에서 바이트 배열을 읽기 */
fun ByteBuffer.getLengthPrefixed(): ByteArray {
    val length = getInt()
    val data = ByteArray(length)
    get(data)
    return data
}

/** String을 UTF-8로 인코딩하여 length-prefixed로 기록 */
fun ByteBuffer.putString(value: String): ByteBuffer {
    return putLengthPrefixed(value.toByteArray(Charsets.UTF_8))
}

/** length-prefixed UTF-8 바이트를 String으로 읽기 */
fun ByteBuffer.getString(): String {
    return String(getLengthPrefixed(), Charsets.UTF_8)
}

/** 고정 크기 페이지 버퍼를 생성 */
fun newPageBuffer(): ByteBuffer {
    return ByteBuffer.allocate(DiskManager.PAGE_SIZE).order(ByteOrder.BIG_ENDIAN)
}
