package gwanbase.playground

import gwanbase.table.Database
import java.nio.file.Files
import java.nio.file.Path

/**
 * 플레이그라운드가 공유하는 [Database] 하나를 보유하고 초기화한다.
 *
 * 영속성은 의도적으로 없다. 생성·[reset] 시 DB 파일과 WAL을 지우고 새로 열어
 * [SampleData]를 적재한다. 누가 `DROP TABLE`을 해도 "초기화" 한 번으로 돌아온다.
 *
 * @param path DB 파일 경로. WAL은 `Database.open` 규약대로 `<path>.wal`에 생긴다.
 */
class Engine(private val path: Path) : AutoCloseable {

    @Volatile
    var database: Database = openFresh()
        private set

    /** 현재 DB를 닫고 파일을 지운 뒤 샘플 상태로 다시 연다. 호출자가 다른 요청을 막아야 한다. */
    fun reset() {
        try {
            database.close()
        } finally {
            database = openFresh()
        }
    }

    private fun openFresh(): Database {
        Files.deleteIfExists(path)
        Files.deleteIfExists(path.resolveSibling(path.fileName.toString() + ".wal"))
        val db = Database.open(path)
        try {
            SampleData.load(db)
        } catch (e: Exception) {
            db.close()
            throw e
        }
        return db
    }

    override fun close() = database.close()
}
