package gwanbase.optimizer

import gwanbase.sql.Expression
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class PlanNodeTest {

    private fun scan(lower: Bound?, upper: Bound?) = PlanNode.IndexScan(
        tableName = "t", indexName = "idx", indexColumnName = "c",
        lowerBound = lower, upperBound = upper, filter = null,
        estimatedRows = 1, estimatedCost = 4.0,
    )

    @Test
    fun `등가 경계는 key=로 출력된다`() {
        val v = Expression.IntLiteral(42)
        val node = scan(Bound(v, true), Bound(v, true))
        node.isEquality.shouldBeTrue()
        node.explain() shouldContain "key=42"
    }

    @Test
    fun `양방향 범위는 포함 여부에 따라 괄호가 달라진다`() {
        val node = scan(Bound(Expression.IntLiteral(20), true), Bound(Expression.IntLiteral(30), false))
        node.isEquality.shouldBeFalse()
        node.explain() shouldContain "range=[20, 30)"
    }

    @Test
    fun `상한 없는 범위는 +inf로 출력된다`() {
        val node = scan(Bound(Expression.IntLiteral(20), false), null)
        node.explain() shouldContain "range=(20, +inf)"
    }

    @Test
    fun `하한 없는 범위는 -inf로 출력된다`() {
        val node = scan(null, Bound(Expression.StringLiteral("abc"), true))
        node.explain() shouldContain "range=(-inf, 'abc']"
    }
}
