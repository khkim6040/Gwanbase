package gwanbase.playground

import gwanbase.table.Database

/**
 * 플레이그라운드 초기 데이터.
 *
 * PRIMARY KEY(유일 인덱스), FOREIGN KEY, 보조 인덱스를 하나씩 넣어 예제 버튼이
 * 23505/23503 에러와 IndexScan을 바로 보여줄 수 있게 한다.
 */
object SampleData {

    val statements: List<String> = listOf(
        "CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50), age INT)",
        "CREATE TABLE orders (id INT PRIMARY KEY, user_id INT REFERENCES users(id), amount INT)",
        "CREATE INDEX idx_orders_user ON orders (user_id)",
        "INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)",
        "INSERT INTO users (id, name, age) VALUES (2, 'Bob', 25)",
        "INSERT INTO users (id, name, age) VALUES (3, 'Carol', 41)",
        "INSERT INTO users (id, name, age) VALUES (4, 'Dave', 35)",
        "INSERT INTO users (id, name, age) VALUES (5, 'Eve', 28)",
        "INSERT INTO orders (id, user_id, amount) VALUES (1, 1, 120)",
        "INSERT INTO orders (id, user_id, amount) VALUES (2, 1, 80)",
        "INSERT INTO orders (id, user_id, amount) VALUES (3, 2, 300)",
        "INSERT INTO orders (id, user_id, amount) VALUES (4, 3, 45)",
        "INSERT INTO orders (id, user_id, amount) VALUES (5, 3, 60)",
        "INSERT INTO orders (id, user_id, amount) VALUES (6, 3, 15)",
        "INSERT INTO orders (id, user_id, amount) VALUES (7, 4, 500)",
        "INSERT INTO orders (id, user_id, amount) VALUES (8, 5, 210)",
        "ANALYZE users",
        "ANALYZE orders",
    )

    /** 빈 데이터베이스에 샘플 스키마와 행을 적재한다. */
    fun load(db: Database) {
        for (sql in statements) db.executeSql(sql)
    }
}
