package gwanbase.playground

import gwanbase.table.Database
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SessionRegistryTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var db: Database
    private lateinit var registry: SessionRegistry

    @BeforeEach
    fun setUp() {
        db = Database.open(tempDir.resolve("t.db"))
        registry = SessionRegistry({ db }, lockTimeoutMillis = 100, idleMillis = 1_000)
    }

    @AfterEach
    fun tearDown() {
        registry.closeAll()
        db.close()
    }

    @Test
    fun `ID가 없으면 새 세션을 만들고 같은 ID로 다시 요청하면 같은 세션을 준다`() {
        val (id, first) = registry.acquire(null)
        val (again, second) = registry.acquire(id)

        again shouldBe id
        second shouldBe first
        registry.size shouldBe 1
    }

    @Test
    fun `모르는 ID로 요청하면 새 ID와 새 세션을 준다`() {
        val (id, _) = registry.acquire("stale-cookie")
        id shouldNotBe "stale-cookie"
        registry.size shouldBe 1
    }

    @Test
    fun `idle 시간을 넘긴 세션만 회수한다`() {
        val (oldId, old) = registry.acquire(null)
        val (freshId, fresh) = registry.acquire(null)
        old.lastUsedAt = 0L
        fresh.lastUsedAt = 10_000L

        registry.evictIdle(now = 5_000L) shouldBe 1

        registry.size shouldBe 1
        registry.acquire(freshId).second shouldBe fresh
        registry.acquire(oldId).first shouldNotBe oldId
    }

    @Test
    fun `회수된 세션은 닫혀서 미커밋 트랜잭션의 잠금이 풀린다`() {
        db.executeSql("CREATE TABLE t (id INT PRIMARY KEY)")
        db.executeSql("INSERT INTO t (id) VALUES (1)")
        val (_, holder) = registry.acquire(null)
        holder.execute("BEGIN")
        holder.execute("UPDATE t SET id = 1 WHERE id = 1")
        holder.lastUsedAt = 0L

        registry.evictIdle(now = 5_000L)

        val (_, other) = registry.acquire(null)
        other.execute("UPDATE t SET id = 1 WHERE id = 1") // 잠금이 남아 있으면 100ms 후 LockTimeoutException
    }

    @Test
    fun `closeAll은 모든 세션을 닫고 비운다`() {
        registry.acquire(null)
        registry.acquire(null)
        registry.closeAll()
        registry.size shouldBe 0
    }

    @Test
    fun `기존 세션을 acquire하면 lastUsedAt이 갱신되어 회수 대상에서 벗어난다`() {
        val (id, session) = registry.acquire(null)
        session.lastUsedAt = 0L

        registry.acquire(id)

        registry.evictIdle(now = 5_000L) shouldBe 0
        registry.size shouldBe 1
    }
}
