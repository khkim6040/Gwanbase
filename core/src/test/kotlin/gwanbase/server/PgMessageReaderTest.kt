package gwanbase.server

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class PgMessageReaderTest {

    private fun buildStartupMessage(params: Map<String, String>): ByteArray {
        val payload = ByteArrayOutputStream()
        val dos = DataOutputStream(payload)
        dos.writeInt(196608)  // version 3.0
        for ((key, value) in params) {
            dos.write(key.toByteArray(Charsets.UTF_8)); dos.write(0)
            dos.write(value.toByteArray(Charsets.UTF_8)); dos.write(0)
        }
        dos.write(0)
        val payloadBytes = payload.toByteArray()
        val result = ByteArrayOutputStream()
        DataOutputStream(result).writeInt(payloadBytes.size + 4)
        result.write(payloadBytes)
        return result.toByteArray()
    }

    private fun buildMessage(type: Char, block: DataOutputStream.() -> Unit): ByteArray {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { it.block() }
        val payloadBytes = payload.toByteArray()
        val result = ByteArrayOutputStream()
        result.write(type.code)
        DataOutputStream(result).writeInt(payloadBytes.size + 4)
        result.write(payloadBytes)
        return result.toByteArray()
    }

    @Test
    fun `StartupMessage 파싱 — version 3점0과 파라미터 추출`() {
        val bytes = buildStartupMessage(mapOf("user" to "gwanbase", "database" to "testdb"))
        val reader = PgMessageReader(ByteArrayInputStream(bytes))
        val msg = reader.readStartupMessage()
        msg.shouldBeInstanceOf<PgMessage.StartupMessage>()
        msg.version shouldBe 196608
        msg.params["user"] shouldBe "gwanbase"
        msg.params["database"] shouldBe "testdb"
    }

    @Test
    fun `SSLRequest 감지 — 길이 8, 코드 80877103은 null 반환`() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(8)
        dos.writeInt(80877103)
        val bytes = baos.toByteArray()
        val reader = PgMessageReader(ByteArrayInputStream(bytes))
        val result = reader.readStartupMessage()
        result shouldBe null
    }

    @Test
    fun `Query 메시지 파싱 — null-terminated SQL 추출`() {
        val bytes = buildMessage('Q') {
            write("SELECT * FROM t".toByteArray(Charsets.UTF_8))
            write(0)
        }
        val reader = PgMessageReader(ByteArrayInputStream(bytes))
        val msg = reader.readMessage()
        msg.shouldBeInstanceOf<PgMessage.Query>()
        msg.sql shouldBe "SELECT * FROM t"
    }

    @Test
    fun `Terminate 메시지 파싱 — 페이로드 없음`() {
        val bytes = buildMessage('X') {}
        val reader = PgMessageReader(ByteArrayInputStream(bytes))
        val msg = reader.readMessage()
        msg.shouldBeInstanceOf<PgMessage.Terminate>()
    }
}
