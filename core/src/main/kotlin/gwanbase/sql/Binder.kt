package gwanbase.sql

import gwanbase.table.Catalog
import gwanbase.table.Schema

/**
 * AST를 Catalog과 대조하여 검증하는 바인더.
 *
 * 검증만 수행하며 AST를 변환하지 않는다. 검증 실패 시 [BindException]을 던진다.
 */
class Binder(private val catalog: Catalog) {

    /**
     * 주어진 SQL 문을 바인딩(검증)한다.
     *
     * @return 검증을 통과한 동일한 [Statement]
     * @throws BindException 테이블/컬럼 검증 실패 시
     */
    fun bind(statement: Statement): Statement {
        when (statement) {
            is Statement.CreateTable -> bindCreateTable(statement)
            is Statement.DropTable -> bindDropTable(statement)
            is Statement.Insert -> bindInsert(statement)
            is Statement.Select -> bindSelect(statement)
            is Statement.Update -> bindUpdate(statement)
            is Statement.Delete -> bindDelete(statement)
        }
        return statement
    }

    private fun bindCreateTable(stmt: Statement.CreateTable) {
        if (catalog.getTable(stmt.tableName) != null) {
            throw BindException("테이블 '${stmt.tableName}'이 이미 존재한다")
        }
    }

    private fun bindDropTable(stmt: Statement.DropTable) {
        requireTable(stmt.tableName)
    }

    private fun bindInsert(stmt: Statement.Insert) {
        val schema = requireTable(stmt.tableName)

        // 컬럼 수와 값 수 일치 검증
        if (stmt.columns.size != stmt.values.size) {
            throw BindException(
                "컬럼 수(${stmt.columns.size})와 값 수(${stmt.values.size})가 일치하지 않는다"
            )
        }

        // 중복 컬럼 검증
        val seen = mutableSetOf<String>()
        for (colName in stmt.columns) {
            if (!seen.add(colName)) {
                throw BindException("중복된 컬럼 '$colName'이 지정되었다")
            }
        }

        // 컬럼 존재 검증
        for (colName in stmt.columns) {
            requireColumn(schema, colName)
        }

        // NOT NULL 검증: 컬럼과 값을 매칭하여 NULL 리터럴 확인
        for ((index, value) in stmt.values.withIndex()) {
            if (value is Expression.NullLiteral) {
                val colName = stmt.columns[index]
                val colIdx = schema.columnIndex(colName)
                val column = schema.column(colIdx)
                if (!column.nullable) {
                    throw BindException("NOT NULL 컬럼 '${colName}'에 NULL을 삽입할 수 없다")
                }
            }
        }

        // NOT NULL 컬럼 누락 검증
        for (i in 0 until schema.columnCount) {
            val column = schema.column(i)
            if (!column.nullable && column.name !in seen) {
                throw BindException("NOT NULL 컬럼 '${column.name}'이 INSERT 컬럼 목록에 누락되었다")
            }
        }
    }

    private fun bindSelect(stmt: Statement.Select) {
        val schema = requireTable(stmt.tableName)

        // SELECT 목록 검증
        for (item in stmt.columns) {
            when (item) {
                is SelectItem.Star -> { /* 모든 컬럼 — 검증 불필요 */ }
                is SelectItem.ExprItem -> validateExpression(schema, item.expr)
            }
        }

        // WHERE 절 검증
        if (stmt.where != null) {
            validateExpression(schema, stmt.where)
        }

        // ORDER BY 절 검증
        if (stmt.orderBy != null) {
            requireColumn(schema, stmt.orderBy.column)
        }
    }

    private fun bindUpdate(stmt: Statement.Update) {
        val schema = requireTable(stmt.tableName)

        // SET 절 컬럼 검증
        for (assignment in stmt.assignments) {
            requireColumn(schema, assignment.column)
            validateExpression(schema, assignment.value)
        }

        // WHERE 절 검증
        if (stmt.where != null) {
            validateExpression(schema, stmt.where)
        }
    }

    private fun bindDelete(stmt: Statement.Delete) {
        val schema = requireTable(stmt.tableName)

        // WHERE 절 검증
        if (stmt.where != null) {
            validateExpression(schema, stmt.where)
        }
    }

    /**
     * 테이블이 Catalog에 존재하는지 확인하고 스키마를 반환한다.
     *
     * @throws BindException 테이블이 존재하지 않을 때
     */
    private fun requireTable(name: String): Schema {
        val tableInfo = catalog.getTable(name)
            ?: throw BindException("테이블 '$name'이 존재하지 않는다")
        return tableInfo.schema
    }

    /**
     * 컬럼이 스키마에 존재하는지 확인한다.
     *
     * @throws BindException 컬럼이 존재하지 않을 때
     */
    private fun requireColumn(schema: Schema, columnName: String) {
        try {
            schema.columnIndex(columnName)
        } catch (e: IllegalArgumentException) {
            throw BindException("컬럼 '$columnName'이 스키마에 존재하지 않는다")
        }
    }

    /**
     * 표현식 내의 컬럼 참조가 스키마에 존재하는지 재귀적으로 검증한다.
     *
     * @throws BindException 존재하지 않는 컬럼 참조 시
     */
    private fun validateExpression(schema: Schema, expr: Expression) {
        when (expr) {
            is Expression.ColumnRef -> requireColumn(schema, expr.name)
            is Expression.BinaryOp -> {
                validateExpression(schema, expr.left)
                validateExpression(schema, expr.right)
            }
            is Expression.UnaryOp -> validateExpression(schema, expr.operand)
            is Expression.IsNull -> validateExpression(schema, expr.expr)
            is Expression.IsNotNull -> validateExpression(schema, expr.expr)
            is Expression.IntLiteral,
            is Expression.FloatLiteral,
            is Expression.StringLiteral,
            is Expression.BoolLiteral,
            is Expression.NullLiteral -> { /* 리터럴 — 검증 불필요 */ }
        }
    }
}
