package gwanbase.server

import gwanbase.table.Database
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path

class ConnectionHandlerTest {

    @TempDir
    lateinit var tempDir: Path
    private lateinit var db: Database

    @BeforeEach
    fun setUp() {
        db = Database.open(tempDir.resolve("test.db"))
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    private fun startHandler(): Pair<Socket, Thread> {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val thread = Thread {
            val accepted = serverSocket.accept()
            serverSocket.close()
            ConnectionHandler(accepted, db).run()
        }
        thread.isDaemon = true
        thread.start()
        val client = Socket("localhost", port)
        return client to thread
    }

    private fun sendStartup(out: java.io.OutputStream) {
        val payload = ByteArrayOutputStream()
        val dos = DataOutputStream(payload)
        dos.writeInt(196608)
        dos.write("user".toByteArray()); dos.write(0)
        dos.write("gwanbase".toByteArray()); dos.write(0)
        dos.write(0)
        val payloadBytes = payload.toByteArray()
        DataOutputStream(out).writeInt(payloadBytes.size + 4)
        out.write(payloadBytes)
        out.flush()
    }

    private fun sendQuery(out: java.io.OutputStream, sql: String) {
        val sqlBytes = sql.toByteArray(Charsets.UTF_8)
        out.write('Q'.code)
        DataOutputStream(out).writeInt(sqlBytes.size + 4 + 1)
        out.write(sqlBytes)
        out.write(0)
        out.flush()
    }

    private fun sendTerminate(out: java.io.OutputStream) {
        out.write('X'.code)
        DataOutputStream(out).writeInt(4)
        out.flush()
    }

    private fun readUntilReady(reader: PgMessageReader): List<PgMessage> {
        val messages = mutableListOf<PgMessage>()
        while (true) {
            val msg = reader.readMessage()
            messages.add(msg)
            if (msg is PgMessage.ReadyForQuery) break
        }
        return messages
    }

    @Test
    fun `정상 흐름 — Startup부터 Terminate까지`() {
        val (client, thread) = startHandler()
        client.use { sock ->
            val out = sock.getOutputStream()
            val reader = PgMessageReader(sock.getInputStream())

            sendStartup(out)
            val startupMsgs = readUntilReady(reader)
            startupMsgs.first().shouldBeInstanceOf<PgMessage.AuthenticationOk>()
            startupMsgs.last().shouldBeInstanceOf<PgMessage.ReadyForQuery>()
            (startupMsgs.last() as PgMessage.ReadyForQuery).txnStatus shouldBe 'I'

            sendTerminate(out)
        }
        thread.join(3000)
    }

    @Test
    fun `SELECT — CREATE TABLE 후 INSERT 후 SELECT`() {
        val (client, thread) = startHandler()
        client.use { sock ->
            val out = sock.getOutputStream()
            val reader = PgMessageReader(sock.getInputStream())

            sendStartup(out)
            readUntilReady(reader)

            sendQuery(out, "CREATE TABLE t (id INT NOT NULL, name VARCHAR(50))")
            val createMsgs = readUntilReady(reader)
            createMsgs.any { it is PgMessage.CommandComplete } shouldBe true

            sendQuery(out, "INSERT INTO t (id, name) VALUES (1, 'alice')")
            readUntilReady(reader)

            sendQuery(out, "SELECT * FROM t")
            val selectMsgs = readUntilReady(reader)
            selectMsgs.any { it is PgMessage.RowDescription } shouldBe true
            val dataRows = selectMsgs.filterIsInstance<PgMessage.DataRow>()
            dataRows.size shouldBe 1

            sendTerminate(out)
        }
        thread.join(3000)
    }

    @Test
    fun `트랜잭션 — BEGIN, INSERT, COMMIT`() {
        val (client, thread) = startHandler()
        client.use { sock ->
            val out = sock.getOutputStream()
            val reader = PgMessageReader(sock.getInputStream())

            sendStartup(out)
            readUntilReady(reader)

            sendQuery(out, "CREATE TABLE t (id INT NOT NULL)")
            readUntilReady(reader)

            sendQuery(out, "BEGIN")
            val beginMsgs = readUntilReady(reader)
            (beginMsgs.last() as PgMessage.ReadyForQuery).txnStatus shouldBe 'T'

            sendQuery(out, "INSERT INTO t (id) VALUES (1)")
            val insertMsgs = readUntilReady(reader)
            (insertMsgs.last() as PgMessage.ReadyForQuery).txnStatus shouldBe 'T'

            sendQuery(out, "COMMIT")
            val commitMsgs = readUntilReady(reader)
            (commitMsgs.last() as PgMessage.ReadyForQuery).txnStatus shouldBe 'I'

            sendTerminate(out)
        }
        thread.join(3000)
    }

    @Test
    fun `에러 복구 — 잘못된 SQL 후에도 세션 유지`() {
        val (client, thread) = startHandler()
        client.use { sock ->
            val out = sock.getOutputStream()
            val reader = PgMessageReader(sock.getInputStream())

            sendStartup(out)
            readUntilReady(reader)

            sendQuery(out, "SELECT * FROM nonexistent")
            val errorMsgs = readUntilReady(reader)
            errorMsgs.any { it is PgMessage.ErrorResponse } shouldBe true
            (errorMsgs.last() as PgMessage.ReadyForQuery).txnStatus shouldBe 'I'

            sendQuery(out, "CREATE TABLE t (id INT NOT NULL)")
            val createMsgs = readUntilReady(reader)
            createMsgs.any { it is PgMessage.CommandComplete } shouldBe true

            sendTerminate(out)
        }
        thread.join(3000)
    }

    @Test
    fun `트랜잭션 에러 상태 — ROLLBACK 전까지 명령 거부`() {
        val (client, thread) = startHandler()
        client.use { sock ->
            val out = sock.getOutputStream()
            val reader = PgMessageReader(sock.getInputStream())

            sendStartup(out)
            readUntilReady(reader)

            sendQuery(out, "CREATE TABLE t (id INT NOT NULL)")
            readUntilReady(reader)

            sendQuery(out, "BEGIN")
            readUntilReady(reader)

            sendQuery(out, "SELECT * FROM nonexistent")
            val errorMsgs = readUntilReady(reader)
            (errorMsgs.last() as PgMessage.ReadyForQuery).txnStatus shouldBe 'E'

            sendQuery(out, "SELECT * FROM t")
            val rejectedMsgs = readUntilReady(reader)
            rejectedMsgs.any { it is PgMessage.ErrorResponse } shouldBe true
            (rejectedMsgs.last() as PgMessage.ReadyForQuery).txnStatus shouldBe 'E'

            sendQuery(out, "ROLLBACK")
            val rollbackMsgs = readUntilReady(reader)
            (rollbackMsgs.last() as PgMessage.ReadyForQuery).txnStatus shouldBe 'I'

            sendTerminate(out)
        }
        thread.join(3000)
    }
}
