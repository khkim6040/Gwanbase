package gwanbase.sql

/**
 * SQL 재귀 하강 파서.
 *
 * 토큰 리스트를 입력받아 AST([Statement])를 생성한다.
 * 표현식 파싱에는 Pratt parsing(우선순위 클라이밍)을 사용한다.
 *
 * @param tokens 렉서가 생성한 토큰 리스트 (마지막은 [TokenType.EOF])
 */
class Parser(private val tokens: List<Token>) {

    private var pos: Int = 0

    /**
     * SQL 문 하나를 파싱한다.
     *
     * 문 뒤에 선택적 세미콜론을 허용하며, 이후 EOF를 기대한다.
     *
     * @return 파싱된 [Statement]
     * @throws ParseException 문법 오류 시
     */
    fun parse(): Statement {
        val stmt = parseStatement()
        // 선택적 세미콜론
        if (peek().type == TokenType.SEMICOLON) {
            advance()
        }
        expect(TokenType.EOF, "문장 끝에 예상하지 못한 토큰이 있다")
        return stmt
    }

    // ── 문(statement) 파싱 ──

    private fun parseStatement(): Statement {
        return when (peek().type) {
            TokenType.CREATE -> parseCreate()
            TokenType.DROP -> parseDrop()
            TokenType.SELECT -> parseSelect()
            TokenType.INSERT -> parseInsert()
            TokenType.UPDATE -> parseUpdate()
            TokenType.DELETE -> parseDelete()
            TokenType.ANALYZE -> parseAnalyze()
            TokenType.EXPLAIN -> parseExplain()
            TokenType.BEGIN -> { advance(); Statement.Begin }
            TokenType.COMMIT -> { advance(); Statement.Commit }
            TokenType.ROLLBACK -> { advance(); Statement.Rollback }
            else -> throw ParseException("예상하지 못한 토큰: '${peek().literal}'", peek().position)
        }
    }

    // ── CREATE (TABLE | INDEX) ──

    /**
     * CREATE 문을 파싱한다. TABLE 또는 INDEX로 분기한다.
     */
    private fun parseCreate(): Statement {
        expect(TokenType.CREATE, "CREATE 키워드가 필요하다")
        return when (peek().type) {
            TokenType.TABLE -> parseCreateTableBody()
            TokenType.INDEX -> parseCreateIndex()
            else -> throw ParseException("CREATE 뒤에 TABLE 또는 INDEX가 필요하다", peek().position)
        }
    }

    /**
     * CREATE TABLE 문의 본문을 파싱한다.
     *
     * 문법: TABLE name (coldef, coldef, ...)
     */
    private fun parseCreateTableBody(): Statement.CreateTable {
        expect(TokenType.TABLE, "TABLE 키워드가 필요하다")
        val tableName = expectIdentifier("테이블 이름이 필요하다")

        expect(TokenType.LPAREN, "'(' 가 필요하다")
        val columns = mutableListOf<ColumnDef>()
        columns.add(parseColumnDef())
        while (peek().type == TokenType.COMMA) {
            advance() // COMMA 소비
            columns.add(parseColumnDef())
        }
        expect(TokenType.RPAREN, "')' 가 필요하다")

        return Statement.CreateTable(tableName, columns)
    }

    /**
     * 컬럼 정의를 파싱한다.
     *
     * 문법: name datatype [NOT NULL]
     */
    private fun parseColumnDef(): ColumnDef {
        val name = expectIdentifier("컬럼 이름이 필요하다")
        val dataType = parseDataType()
        var nullable = true
        if (peek().type == TokenType.NOT) {
            advance() // NOT 소비
            expect(TokenType.NULL, "NOT 뒤에 NULL이 필요하다")
            nullable = false
        }
        return ColumnDef(name, dataType, nullable)
    }

    /**
     * 데이터 타입을 파싱한다.
     *
     * 지원 타입: BOOLEAN, INT, INTEGER, BIGINT, DOUBLE, FLOAT, TIMESTAMP, VARCHAR(n)
     */
    private fun parseDataType(): SqlDataType {
        val token = peek()
        return when (token.type) {
            TokenType.BOOLEAN -> {
                advance()
                SqlDataType.BooleanType
            }
            TokenType.INT, TokenType.INTEGER -> {
                advance()
                SqlDataType.IntType
            }
            TokenType.BIGINT -> {
                advance()
                SqlDataType.BigIntType
            }
            TokenType.DOUBLE, TokenType.FLOAT -> {
                advance()
                SqlDataType.DoubleType
            }
            TokenType.TIMESTAMP -> {
                advance()
                SqlDataType.TimestampType
            }
            TokenType.VARCHAR -> {
                advance()
                expect(TokenType.LPAREN, "VARCHAR 뒤에 '(' 가 필요하다")
                val lengthToken = expect(TokenType.INTEGER_LITERAL, "VARCHAR 길이가 필요하다")
                val maxLength = lengthToken.literal.toInt()
                expect(TokenType.RPAREN, "VARCHAR 길이 뒤에 ')' 가 필요하다")
                SqlDataType.VarcharType(maxLength)
            }
            else -> throw ParseException("데이터 타입이 필요하다, 발견: '${token.literal}'", token.position)
        }
    }

    /**
     * CREATE INDEX 문을 파싱한다.
     *
     * 문법: INDEX indexName ON tableName (columnName)
     */
    private fun parseCreateIndex(): Statement.CreateIndex {
        expect(TokenType.INDEX, "INDEX 키워드가 필요하다")
        val indexName = expectIdentifier("인덱스 이름이 필요하다")
        expect(TokenType.ON, "ON 키워드가 필요하다")
        val tableName = expectIdentifier("테이블 이름이 필요하다")
        expect(TokenType.LPAREN, "'(' 가 필요하다")
        val columnName = expectIdentifier("컬럼 이름이 필요하다")
        expect(TokenType.RPAREN, "')' 가 필요하다")
        return Statement.CreateIndex(indexName, tableName, columnName)
    }

    // ── DROP (TABLE | INDEX) ──

    /**
     * DROP 문을 파싱한다. TABLE 또는 INDEX로 분기한다.
     */
    private fun parseDrop(): Statement {
        expect(TokenType.DROP, "DROP 키워드가 필요하다")
        return when (peek().type) {
            TokenType.TABLE -> parseDropTableBody()
            TokenType.INDEX -> parseDropIndex()
            else -> throw ParseException("DROP 뒤에 TABLE 또는 INDEX가 필요하다", peek().position)
        }
    }

    /**
     * DROP TABLE 문의 본문을 파싱한다.
     *
     * 문법: TABLE name
     */
    private fun parseDropTableBody(): Statement.DropTable {
        expect(TokenType.TABLE, "TABLE 키워드가 필요하다")
        val tableName = expectIdentifier("테이블 이름이 필요하다")
        return Statement.DropTable(tableName)
    }

    /**
     * DROP INDEX 문을 파싱한다.
     *
     * 문법: INDEX indexName
     */
    private fun parseDropIndex(): Statement.DropIndex {
        expect(TokenType.INDEX, "INDEX 키워드가 필요하다")
        val indexName = expectIdentifier("인덱스 이름이 필요하다")
        return Statement.DropIndex(indexName)
    }

    // ── ANALYZE ──

    /**
     * ANALYZE 문을 파싱한다.
     *
     * 문법: ANALYZE tableName
     */
    private fun parseAnalyze(): Statement.Analyze {
        expect(TokenType.ANALYZE, "ANALYZE 키워드가 필요하다")
        val tableName = expectIdentifier("테이블 이름이 필요하다")
        return Statement.Analyze(tableName)
    }

    // ── EXPLAIN ──

    /**
     * EXPLAIN 문을 파싱한다. 내부 문을 감싸서 실행 계획을 표시한다.
     *
     * 문법: EXPLAIN statement
     */
    private fun parseExplain(): Statement.Explain {
        expect(TokenType.EXPLAIN, "EXPLAIN 키워드가 필요하다")
        val inner = parseStatement()
        return Statement.Explain(inner)
    }

    // ── SELECT ──

    /**
     * SELECT 문을 파싱한다.
     *
     * 문법: SELECT selectList FROM name [WHERE expr] [ORDER BY col [ASC|DESC]] [LIMIT n]
     */
    private fun parseSelect(): Statement.Select {
        expect(TokenType.SELECT, "SELECT 키워드가 필요하다")
        val columns = parseSelectList()
        expect(TokenType.FROM, "FROM 키워드가 필요하다")
        val from = parseFromClause()

        // WHERE
        val where = if (peek().type == TokenType.WHERE) {
            advance()
            parseExpression(0)
        } else {
            null
        }

        // ORDER BY
        val orderBy = if (peek().type == TokenType.ORDER) {
            parseOrderBy()
        } else {
            null
        }

        // LIMIT
        val limit = if (peek().type == TokenType.LIMIT) {
            advance()
            val limitToken = expect(TokenType.INTEGER_LITERAL, "LIMIT 값이 필요하다")
            limitToken.literal.toInt()
        } else {
            null
        }

        return Statement.Select(columns, from, where, orderBy, limit)
    }

    /**
     * FROM 절을 파싱한다. JOIN 체이닝을 지원한다.
     *
     * 문법: tableRef [JOIN tableRef ON expr]*
     */
    private fun parseFromClause(): FromClause {
        var left: FromClause = parseTableRef()
        while (peek().type == TokenType.JOIN) {
            advance() // JOIN
            val right = parseTableRef()
            expect(TokenType.ON, "ON 키워드가 필요하다")
            val condition = parseExpression(0)
            left = FromClause.Join(left, right, condition)
        }
        return left
    }

    /**
     * 테이블 참조를 파싱한다. 선택적 별칭을 지원한다.
     *
     * 문법: tableName [alias]
     */
    private fun parseTableRef(): FromClause.Table {
        val tableName = expectIdentifier("테이블 이름이 필요하다")
        val alias = if (peek().type == TokenType.IDENTIFIER) {
            advance().literal
        } else {
            null
        }
        return FromClause.Table(tableName, alias)
    }

    /**
     * SELECT 절의 컬럼 리스트를 파싱한다.
     *
     * 문법: * | expr [, expr ...]
     */
    private fun parseSelectList(): List<SelectItem> {
        if (peek().type == TokenType.STAR) {
            advance()
            return listOf(SelectItem.Star)
        }
        val items = mutableListOf<SelectItem>()
        items.add(SelectItem.ExprItem(parseExpression(0)))
        while (peek().type == TokenType.COMMA) {
            advance()
            items.add(SelectItem.ExprItem(parseExpression(0)))
        }
        return items
    }

    /**
     * ORDER BY 절을 파싱한다.
     *
     * 문법: ORDER BY column [ASC|DESC]
     */
    private fun parseOrderBy(): OrderByClause {
        expect(TokenType.ORDER, "ORDER 키워드가 필요하다")
        expect(TokenType.BY, "BY 키워드가 필요하다")
        val column = expectIdentifier("정렬 컬럼 이름이 필요하다")
        val ascending = when (peek().type) {
            TokenType.ASC -> { advance(); true }
            TokenType.DESC -> { advance(); false }
            else -> true
        }
        return OrderByClause(column, ascending)
    }

    // ── INSERT ──

    /**
     * INSERT 문을 파싱한다.
     *
     * 문법: INSERT INTO tableName (col1, col2, ...) VALUES (expr1, expr2, ...)
     */
    private fun parseInsert(): Statement.Insert {
        advance() // INSERT
        expect(TokenType.INTO, "'INTO' 키워드가 예상되었다")
        val tableName = expectIdentifier("테이블명이 예상되었다")
        expect(TokenType.LPAREN, "'(' 가 예상되었다 (컬럼 목록)")
        val columns = mutableListOf<String>()
        columns.add(expectIdentifier("컬럼명이 예상되었다"))
        while (peek().type == TokenType.COMMA) { advance(); columns.add(expectIdentifier("컬럼명이 예상되었다")) }
        expect(TokenType.RPAREN, "')' 가 예상되었다")
        expect(TokenType.VALUES, "'VALUES' 키워드가 예상되었다")
        expect(TokenType.LPAREN, "'(' 가 예상되었다 (값 목록)")
        val values = mutableListOf<Expression>()
        values.add(parseExpression(0))
        while (peek().type == TokenType.COMMA) { advance(); values.add(parseExpression(0)) }
        expect(TokenType.RPAREN, "')' 가 예상되었다")
        return Statement.Insert(tableName, columns, values)
    }

    // ── UPDATE ──

    /**
     * UPDATE 문을 파싱한다.
     *
     * 문법: UPDATE tableName SET col1 = expr1, col2 = expr2, ... [WHERE condition]
     */
    private fun parseUpdate(): Statement.Update {
        advance() // UPDATE
        val tableName = expectIdentifier("테이블명이 예상되었다")
        expect(TokenType.SET, "'SET' 키워드가 예상되었다")
        val assignments = mutableListOf<Assignment>()
        assignments.add(parseAssignment())
        while (peek().type == TokenType.COMMA) { advance(); assignments.add(parseAssignment()) }
        val where = if (peek().type == TokenType.WHERE) { advance(); parseExpression(0) } else null
        return Statement.Update(tableName, assignments, where)
    }

    /**
     * SET 절의 대입을 파싱한다.
     *
     * 문법: column = expr
     */
    private fun parseAssignment(): Assignment {
        val column = expectIdentifier("컬럼명이 예상되었다")
        expect(TokenType.EQ, "'=' 가 예상되었다")
        val value = parseExpression(0)
        return Assignment(column, value)
    }

    // ── DELETE ──

    /**
     * DELETE 문을 파싱한다.
     *
     * 문법: DELETE FROM tableName [WHERE condition]
     */
    private fun parseDelete(): Statement.Delete {
        advance() // DELETE
        expect(TokenType.FROM, "'FROM' 키워드가 예상되었다")
        val tableName = expectIdentifier("테이블명이 예상되었다")
        val where = if (peek().type == TokenType.WHERE) { advance(); parseExpression(0) } else null
        return Statement.Delete(tableName, where)
    }

    // ── Pratt parsing (표현식) ──

    /**
     * 표현식을 Pratt parsing으로 파싱한다.
     *
     * @param minPrecedence 최소 우선순위 (이보다 낮은 연산자를 만나면 리턴)
     * @return 파싱된 [Expression]
     */
    private fun parseExpression(minPrecedence: Int): Expression {
        var left = parsePrefixExpression()

        // IS NULL / IS NOT NULL (후위 연산, 비교 우선순위 4)
        while (peek().type == TokenType.IS && minPrecedence <= 4) {
            advance() // IS 소비
            if (peek().type == TokenType.NOT) {
                advance() // NOT 소비
                expect(TokenType.NULL, "IS NOT 뒤에 NULL이 필요하다")
                left = Expression.IsNotNull(left)
            } else {
                expect(TokenType.NULL, "IS 뒤에 NULL이 필요하다")
                left = Expression.IsNull(left)
            }
        }

        while (true) {
            val op = peekBinaryOperator() ?: break
            val prec = binaryPrecedence(op)
            if (prec < minPrecedence) break
            advance() // 연산자 토큰 소비
            // 좌결합: 오른쪽은 prec + 1
            val right = parseExpression(prec + 1)
            left = Expression.BinaryOp(left, op, right)
        }

        return left
    }

    /**
     * 접두(prefix) 표현식을 파싱한다.
     *
     * 리터럴, NOT, 단항 부정, 괄호, 컬럼 참조를 처리한다.
     */
    private fun parsePrefixExpression(): Expression {
        val token = peek()
        return when (token.type) {
            TokenType.INTEGER_LITERAL -> {
                advance()
                Expression.IntLiteral(token.literal.toLong())
            }
            TokenType.FLOAT_LITERAL -> {
                advance()
                Expression.FloatLiteral(token.literal.toDouble())
            }
            TokenType.STRING_LITERAL -> {
                advance()
                Expression.StringLiteral(token.literal)
            }
            TokenType.TRUE -> {
                advance()
                Expression.BoolLiteral(true)
            }
            TokenType.FALSE -> {
                advance()
                Expression.BoolLiteral(false)
            }
            TokenType.NULL -> {
                advance()
                Expression.NullLiteral
            }
            TokenType.NOT -> {
                advance()
                val operand = parseExpression(3) // NOT 우선순위 = 3
                Expression.UnaryOp(UnaryOperator.NOT, operand)
            }
            TokenType.MINUS -> {
                advance()
                val operand = parseExpression(7) // 단항 부정 우선순위 = 7
                Expression.UnaryOp(UnaryOperator.NEGATE, operand)
            }
            TokenType.LPAREN -> {
                advance() // '(' 소비
                val expr = parseExpression(0)
                expect(TokenType.RPAREN, "')' 가 필요하다")
                expr
            }
            TokenType.IDENTIFIER -> {
                advance()
                if (peek().type == TokenType.DOT) {
                    advance() // DOT
                    val colName = expectIdentifier("컬럼 이름이 필요하다")
                    Expression.ColumnRef(token.literal, colName)
                } else {
                    Expression.ColumnRef(null, token.literal)
                }
            }
            else -> throw ParseException(
                "표현식이 필요하다, 발견: '${token.literal}'",
                token.position,
            )
        }
    }

    /**
     * 현재 토큰이 이항 연산자인 경우 해당 [BinaryOperator]를 반환한다.
     * 이항 연산자가 아니면 null을 반환한다.
     */
    private fun peekBinaryOperator(): BinaryOperator? {
        return when (peek().type) {
            TokenType.OR -> BinaryOperator.OR
            TokenType.AND -> BinaryOperator.AND
            TokenType.EQ -> BinaryOperator.EQ
            TokenType.NEQ -> BinaryOperator.NEQ
            TokenType.LT -> BinaryOperator.LT
            TokenType.GT -> BinaryOperator.GT
            TokenType.LTE -> BinaryOperator.LTE
            TokenType.GTE -> BinaryOperator.GTE
            TokenType.PLUS -> BinaryOperator.ADD
            TokenType.MINUS -> BinaryOperator.SUB
            TokenType.STAR -> BinaryOperator.MUL
            TokenType.SLASH -> BinaryOperator.DIV
            else -> null
        }
    }

    /**
     * 이항 연산자의 우선순위를 반환한다.
     */
    private fun binaryPrecedence(op: BinaryOperator): Int {
        return when (op) {
            BinaryOperator.OR -> 1
            BinaryOperator.AND -> 2
            BinaryOperator.EQ, BinaryOperator.NEQ,
            BinaryOperator.LT, BinaryOperator.GT,
            BinaryOperator.LTE, BinaryOperator.GTE -> 4
            BinaryOperator.ADD, BinaryOperator.SUB -> 5
            BinaryOperator.MUL, BinaryOperator.DIV -> 6
        }
    }

    // ── 유틸리티 ──

    /**
     * 현재 위치의 토큰을 반환한다 (소비하지 않음).
     */
    private fun peek(): Token = tokens[pos]

    /**
     * 현재 토큰을 소비하고 반환한다.
     */
    private fun advance(): Token {
        val token = tokens[pos]
        pos++
        return token
    }

    /**
     * 현재 토큰이 지정된 타입인지 확인하고 소비한다.
     *
     * @param type 기대하는 토큰 타입
     * @param message 불일치 시 오류 메시지
     * @return 소비된 토큰
     * @throws ParseException 타입 불일치 시
     */
    private fun expect(type: TokenType, message: String): Token {
        val token = peek()
        if (token.type != type) {
            throw ParseException(message, token.position)
        }
        return advance()
    }

    /**
     * 현재 토큰이 식별자인지 확인하고 소비한다.
     *
     * @param message 불일치 시 오류 메시지
     * @return 식별자 문자열
     * @throws ParseException 식별자가 아닌 경우
     */
    private fun expectIdentifier(message: String): String {
        val token = expect(TokenType.IDENTIFIER, message)
        return token.literal
    }
}
