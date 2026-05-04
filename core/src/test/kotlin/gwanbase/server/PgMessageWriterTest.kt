package gwanbase.server

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PgMessageWriterTest {

    private fun write(msg: PgMessage): ByteArray {
        val baos = ByteArrayOutputStream()
        val writer = PgMessageWriter(baos)
        writer.write(msg)
        writer.flush()
        return baos.toByteArray()
    }

    private fun ByteArray.readInt32(offset: Int): Int =
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt()

    private fun ByteArray.readInt16(offset: Int): Short =
        ByteBuffer.wrap(this, offset, 2).order(ByteOrder.BIG_ENDIAN).getShort()

    @Test
    fun `AuthenticationOk 직렬화 — R 타입, 길이 8, 상태 0`() {
        val bytes = write(PgMessage.AuthenticationOk)
        bytes[0] shouldBe 'R'.code.toByte()
        bytes.readInt32(1) shouldBe 8
        bytes.readInt32(5) shouldBe 0
        bytes.size shouldBe 9
    }

    @Test
    fun `ReadyForQuery 직렬화 — Z 타입, 트랜잭션 상태 바이트`() {
        val bytes = write(PgMessage.ReadyForQuery('I'))
        bytes[0] shouldBe 'Z'.code.toByte()
        bytes.readInt32(1) shouldBe 5
        bytes[5] shouldBe 'I'.code.toByte()
        bytes.size shouldBe 6
    }

    @Test
    fun `CommandComplete 직렬화 — null-terminated 태그`() {
        val bytes = write(PgMessage.CommandComplete("SELECT 3"))
        bytes[0] shouldBe 'C'.code.toByte()
        val tag = "SELECT 3"
        val expectedLen = 4 + tag.toByteArray(Charsets.UTF_8).size + 1
        bytes.readInt32(1) shouldBe expectedLen
        bytes[5 + tag.length] shouldBe 0.toByte()
    }

    @Test
    fun `ParameterStatus 직렬화 — name과 value가 null-terminated`() {
        val bytes = write(PgMessage.ParameterStatus("server_version", "0.8.0"))
        bytes[0] shouldBe 'S'.code.toByte()
        val content = String(bytes, 5, bytes.size - 5, Charsets.UTF_8)
        content shouldBe "server_version\u0000" + "0.8.0\u0000"
    }

    @Test
    fun `BackendKeyData 직렬화 — pid와 secretKey`() {
        val bytes = write(PgMessage.BackendKeyData(pid = 42, secretKey = 0))
        bytes[0] shouldBe 'K'.code.toByte()
        bytes.readInt32(1) shouldBe 12
        bytes.readInt32(5) shouldBe 42
        bytes.readInt32(9) shouldBe 0
        bytes.size shouldBe 13
    }

    @Test
    fun `RowDescription 직렬화 — 컬럼 메타데이터`() {
        val col = ColumnDesc(name = "id", typeOid = 23, typeSize = 4)
        val bytes = write(PgMessage.RowDescription(listOf(col)))
        bytes[0] shouldBe 'T'.code.toByte()
        bytes.readInt16(5) shouldBe 1.toShort()
        // "id" + null(3) + tableOid(4) + columnAttr(2) + typeOid(4) + typeSize(2) + typeMod(4) + formatCode(2)
        // = 3 + 4 + 2 + 4 + 2 + 4 + 2 = 21
        val expectedLen = 4 + 2 + 21
        bytes.readInt32(1) shouldBe expectedLen
    }

    @Test
    fun `DataRow 직렬화 — 텍스트 값과 NULL`() {
        val bytes = write(PgMessage.DataRow(listOf("hello", null, "42")))
        bytes[0] shouldBe 'D'.code.toByte()
        bytes.readInt16(5) shouldBe 3.toShort()

        var offset = 7
        bytes.readInt32(offset) shouldBe 5
        offset += 4
        String(bytes, offset, 5, Charsets.UTF_8) shouldBe "hello"
        offset += 5

        bytes.readInt32(offset) shouldBe -1
        offset += 4

        bytes.readInt32(offset) shouldBe 2
        offset += 4
        String(bytes, offset, 2, Charsets.UTF_8) shouldBe "42"
    }

    @Test
    fun `ErrorResponse 직렬화 — severity, message, code 필드`() {
        val bytes = write(PgMessage.ErrorResponse("ERROR", "table not found", "42P01"))
        bytes[0] shouldBe 'E'.code.toByte()
        val content = String(bytes, 5, bytes.size - 5, Charsets.UTF_8)
        content.contains("ERROR") shouldBe true
        content.contains("42P01") shouldBe true
        content.contains("table not found") shouldBe true
        bytes.last() shouldBe 0.toByte()
    }
}
