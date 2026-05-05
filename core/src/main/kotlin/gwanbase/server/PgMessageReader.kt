package gwanbase.server

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * InputStream에서 PostgreSQL 바이너리 메시지를 파싱한다.
 *
 * [readStartupMessage]는 Startup Phase에서 사용하며 타입 바이트가 없는 특수 형식이다.
 * [readMessage]는 Query Phase에서 사용하며 타입(1) + 길이(4) + 페이로드 형식이다.
 */
class PgMessageReader(input: InputStream) {

    private val din = DataInputStream(input)

    /**
     * Startup 메시지를 읽는다.
     *
     * SSLRequest(길이=8, 코드=80877103)를 감지하면 null을 반환한다.
     * 호출자는 null을 받으면 'N'을 전송하고 다시 readStartupMessage()를 호출한다.
     *
     * @return StartupMessage 또는 SSLRequest일 경우 null
     * @throws EOFException 스트림이 끝난 경우
     */
    fun readStartupMessage(): PgMessage.StartupMessage? {
        val length = din.readInt()
        val payloadSize = length - 4
        val payload = ByteArray(payloadSize)
        din.readFully(payload)

        // SSLRequest 감지
        if (length == 8) {
            val code = ((payload[0].toInt() and 0xFF) shl 24) or
                    ((payload[1].toInt() and 0xFF) shl 16) or
                    ((payload[2].toInt() and 0xFF) shl 8) or
                    (payload[3].toInt() and 0xFF)
            if (code == 80877103) return null
        }

        // version (처음 4바이트)
        val version = ((payload[0].toInt() and 0xFF) shl 24) or
                ((payload[1].toInt() and 0xFF) shl 16) or
                ((payload[2].toInt() and 0xFF) shl 8) or
                (payload[3].toInt() and 0xFF)

        // 파라미터 파싱
        val params = mutableMapOf<String, String>()
        var offset = 4
        while (offset < payloadSize) {
            val key = readCString(payload, offset)
            if (key.isEmpty()) break
            offset += key.toByteArray(Charsets.UTF_8).size + 1
            val value = readCString(payload, offset)
            offset += value.toByteArray(Charsets.UTF_8).size + 1
            params[key] = value
        }

        return PgMessage.StartupMessage(version, params)
    }

    /**
     * 일반 메시지를 읽는다 (타입 1바이트 + 길이 4바이트 + 페이로드).
     *
     * @return 파싱된 PgMessage
     * @throws EOFException 스트림이 끝난 경우
     */
    fun readMessage(): PgMessage {
        val type = din.readByte().toInt().toChar()
        val length = din.readInt()
        val payloadSize = length - 4
        val payload = if (payloadSize > 0) {
            ByteArray(payloadSize).also { din.readFully(it) }
        } else {
            ByteArray(0)
        }

        return when (type) {
            'Q' -> {
                val sql = readCString(payload, 0)
                PgMessage.Query(sql)
            }
            'X' -> PgMessage.Terminate
            'R' -> parseAuthentication(payload)
            'S' -> parseParameterStatus(payload)
            'K' -> parseBackendKeyData(payload)
            'Z' -> PgMessage.ReadyForQuery(payload[0].toInt().toChar())
            'T' -> parseRowDescription(payload)
            'D' -> parseDataRow(payload)
            'C' -> PgMessage.CommandComplete(readCString(payload, 0))
            'E' -> parseErrorResponse(payload)
            else -> error("지원하지 않는 메시지 타입: '$type' (0x${type.code.toString(16)})")
        }
    }

    private fun readCString(data: ByteArray, offset: Int): String {
        var end = offset
        while (end < data.size && data[end] != 0.toByte()) end++
        return String(data, offset, end - offset, Charsets.UTF_8)
    }

    private fun readInt32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)

    private fun readInt16(data: ByteArray, offset: Int): Short =
        (((data[offset].toInt() and 0xFF) shl 8) or
                (data[offset + 1].toInt() and 0xFF)).toShort()

    @Suppress("UNUSED_PARAMETER")
    private fun parseAuthentication(payload: ByteArray): PgMessage {
        // AuthenticationOk = type 0
        return PgMessage.AuthenticationOk
    }

    private fun parseParameterStatus(payload: ByteArray): PgMessage {
        val name = readCString(payload, 0)
        val value = readCString(payload, name.toByteArray(Charsets.UTF_8).size + 1)
        return PgMessage.ParameterStatus(name, value)
    }

    private fun parseBackendKeyData(payload: ByteArray): PgMessage {
        val pid = readInt32(payload, 0)
        val secretKey = readInt32(payload, 4)
        return PgMessage.BackendKeyData(pid, secretKey)
    }

    private fun parseRowDescription(payload: ByteArray): PgMessage {
        val columnCount = readInt16(payload, 0).toInt()
        val columns = mutableListOf<ColumnDesc>()
        var offset = 2
        repeat(columnCount) {
            val name = readCString(payload, offset)
            offset += name.toByteArray(Charsets.UTF_8).size + 1
            val tableOid = readInt32(payload, offset); offset += 4
            val columnAttr = readInt16(payload, offset); offset += 2
            val typeOid = readInt32(payload, offset); offset += 4
            val typeSize = readInt16(payload, offset); offset += 2
            val typeMod = readInt32(payload, offset); offset += 4
            val formatCode = readInt16(payload, offset); offset += 2
            columns.add(ColumnDesc(name, tableOid, columnAttr, typeOid, typeSize, typeMod, formatCode))
        }
        return PgMessage.RowDescription(columns)
    }

    private fun parseDataRow(payload: ByteArray): PgMessage {
        val columnCount = readInt16(payload, 0).toInt()
        val values = mutableListOf<String?>()
        var offset = 2
        repeat(columnCount) {
            val length = readInt32(payload, offset); offset += 4
            if (length == -1) {
                values.add(null)
            } else {
                values.add(String(payload, offset, length, Charsets.UTF_8))
                offset += length
            }
        }
        return PgMessage.DataRow(values)
    }

    private fun parseErrorResponse(payload: ByteArray): PgMessage {
        var offset = 0
        var severity = "ERROR"
        var message = ""
        var code = "XX000"
        while (offset < payload.size) {
            val fieldType = payload[offset].toInt().toChar(); offset++
            if (fieldType == '\u0000') break
            val value = readCString(payload, offset)
            offset += value.toByteArray(Charsets.UTF_8).size + 1
            when (fieldType) {
                'S' -> severity = value
                'M' -> message = value
                'C' -> code = value
            }
        }
        return PgMessage.ErrorResponse(severity, message, code)
    }
}
