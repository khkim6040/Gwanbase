package gwanbase

import gwanbase.sql.ExecuteResult
import gwanbase.table.Database
import gwanbase.txn.DatabaseSession
import org.openjdk.jmh.annotations.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * SQL end-to-end 벤치마크.
 *
 * 테이블 생성 → 데이터 삽입 → 쿼리 실행까지 전체 경로 throughput을 측정한다.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class SqlBenchmark {

    private lateinit var tempDir: Path
    private lateinit var database: Database
    private lateinit var session: DatabaseSession
    private var insertCounter = 0

    @Param("100", "1000")
    open var preloadRows: Int = 0

    @Setup(Level.Trial)
    fun setup() {
        tempDir = Files.createTempDirectory("bench-sql")
        database = Database.open(tempDir.resolve("bench.db"), bufferPoolSize = 1024)
        session = database.createSession()

        session.executeSql("CREATE TABLE bench (id INTEGER, name VARCHAR(100), score INTEGER)")

        for (i in 1..preloadRows) {
            session.executeSql("INSERT INTO bench (id, name, score) VALUES ($i, 'user-$i', ${i % 100})")
        }
        insertCounter = preloadRows
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        session.close()
        database.close()
        tempDir.toFile().deleteRecursively()
    }

    @Benchmark
    fun insert(): ExecuteResult {
        insertCounter++
        return session.executeSql("INSERT INTO bench (id, name, score) VALUES ($insertCounter, 'user-$insertCounter', ${insertCounter % 100})")
    }

    @Benchmark
    fun selectById(): ExecuteResult {
        val id = (1..preloadRows).random()
        return session.executeSql("SELECT * FROM bench WHERE id = $id")
    }

    @Benchmark
    fun selectWithFilter(): ExecuteResult {
        return session.executeSql("SELECT name, score FROM bench WHERE score > 50")
    }

    @Benchmark
    fun updateSingleRow(): ExecuteResult {
        val id = (1..preloadRows).random()
        return session.executeSql("UPDATE bench SET score = 99 WHERE id = $id")
    }
}
