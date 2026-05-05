package gwanbase.server

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream

/**
 * PgMessage를 PostgreSQL 바이너리 포맷으로 직렬화한다.
 *
 * 내부적으로 ByteArrayOutputStream에 페이로드를 먼저 조립한 뒤,
 * 타입 바이트 + 길이 + 페이로드 순서로 출력한다.
 */
class PgMessageWriter(private val output: OutputStream) {

    /** 메시지를 직렬화하여 출력 스트림에 쓴다. */
    fun write(msg: PgMessage) {
        when (msg) {
            is PgMessage.AuthenticationOk -> writeAuthenticationOk()
            is PgMessage.ParameterStatus -> writeParameterStatus(msg)
            is PgMessage.BackendKeyData -> writeBackendKeyData(msg)
            is PgMessage.ReadyForQuery -> writeReadyForQuery(msg)
            is PgMessage.RowDescription -> writeRowDescription(msg)
            is PgMessage.DataRow -> writeDataRow(msg)
            is PgMessage.CommandComplete -> writeCommandComplete(msg)
            is PgMessage.ErrorResponse -> writeErrorResponse(msg)
            else -> error("서버에서 전송할 수 없는 메시지 타입: ${msg::class.simpleName}")
        }
    }

    /** 버퍼를 플러시한다. */
    fun flush() {
        output.flush()
    }

    private fun writeMessage(type: Char, block: DataOutputStream.() -> Unit) {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { it.block() }
        val payloadBytes = payload.toByteArray()
        output.write(type.code)
        writeInt32(payloadBytes.size + 4)
        output.write(payloadBytes)
    }

    private fun writeInt32(value: Int) {
        output.write((value shr 24) and 0xFF)
        output.write((value shr 16) and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    private fun DataOutputStream.writeCString(s: String) {
        write(s.toByteArray(Charsets.UTF_8))
        write(0)
    }

    private fun DataOutputStream.writeInt16(value: Short) {
        writeShort(value.toInt())
    }

    private fun writeAuthenticationOk() {
        writeMessage('R') { writeInt(0) }
    }

    private fun writeParameterStatus(msg: PgMessage.ParameterStatus) {
        writeMessage('S') {
            writeCString(msg.name)
            writeCString(msg.value)
        }
    }

    private fun writeBackendKeyData(msg: PgMessage.BackendKeyData) {
        writeMessage('K') {
            writeInt(msg.pid)
            writeInt(msg.secretKey)
        }
    }

    private fun writeReadyForQuery(msg: PgMessage.ReadyForQuery) {
        writeMessage('Z') { write(msg.txnStatus.code) }
    }

    private fun writeRowDescription(msg: PgMessage.RowDescription) {
        writeMessage('T') {
            writeInt16(msg.columns.size.toShort())
            for (col in msg.columns) {
                writeCString(col.name)
                writeInt(col.tableOid)
                writeInt16(col.columnAttr)
                writeInt(col.typeOid)
                writeInt16(col.typeSize)
                writeInt(col.typeMod)
                writeInt16(col.formatCode)
            }
        }
    }

    private fun writeDataRow(msg: PgMessage.DataRow) {
        writeMessage('D') {
            writeInt16(msg.values.size.toShort())
            for (value in msg.values) {
                if (value == null) {
                    writeInt(-1)
                } else {
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    writeInt(bytes.size)
                    write(bytes)
                }
            }
        }
    }

    private fun writeCommandComplete(msg: PgMessage.CommandComplete) {
        writeMessage('C') { writeCString(msg.tag) }
    }

    private fun writeErrorResponse(msg: PgMessage.ErrorResponse) {
        writeMessage('E') {
            write('S'.code); writeCString(msg.severity)
            write('V'.code); writeCString(msg.severity)
            write('C'.code); writeCString(msg.code)
            write('M'.code); writeCString(msg.message)
            write(0)
        }
    }
}
