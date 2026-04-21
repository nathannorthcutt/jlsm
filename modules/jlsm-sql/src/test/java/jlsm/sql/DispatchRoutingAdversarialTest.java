package jlsm.sql;

import jlsm.table.FieldType;
import jlsm.table.JlsmSchema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for dispatch routing concerns in the SQL translator.
 */
class DispatchRoutingAdversarialTest {

    private static final JlsmSchema SCHEMA = JlsmSchema.builder("test", 1)
            .field("name", FieldType.string()).field("age", FieldType.int32())
            .field("salary", FieldType.float64()).field("active", FieldType.boolean_())
            .field("embedding", FieldType.vector(FieldType.Primitive.FLOAT32, 128)).build();

    private final SqlLexer lexer = new SqlLexer();
    private final SqlParser parser = new SqlParser();
    private final SqlTranslator translator = new SqlTranslator();

    private SqlQuery translate(String sql) throws SqlParseException {
        return translator.translate(parser.parse(lexer.tokenize(sql)), SCHEMA);
    }

    // @spec query.sql-query-support.R62
    // Finding: F-R1.dispatch_routing.1.2
    // Bug: translateComparison has 2 branches for 4 cases — both-values (e.g., WHERE 5 = 10)
    // and both-fields (e.g., WHERE name = age) fall into the else branch, producing
    // misleading error messages that blame the wrong operand
    // Correct behavior: both-values should produce an error saying neither operand is a column;
    // both-fields should produce an error saying comparison requires a value operand
    // Fix location: SqlTranslator.translateComparison (lines 162-171)
    // Regression watch: must not break normal field=value or reversed value=field comparisons
    @Test
    void test_translateComparison_bothValuesDispatch_errorDescribesActualProblem() {
        // WHERE 5 = 10 — both operands are value expressions.
        // Current: falls into else branch, calls extractFieldName(NumberLiteral) →
        // "Expected column reference but found NumberLiteral" — misleading, implies
        // the left operand should have been a column rather than explaining that
        // a comparison requires at least one column reference.
        var ex = assertThrows(SqlParseException.class,
                () -> translate("SELECT * FROM test WHERE 5 = 10"));
        // The error should indicate the real problem: no column reference in comparison
        assertTrue(
                ex.getMessage().toLowerCase().contains("column")
                        && ex.getMessage().toLowerCase().contains("comparison"),
                "Error should mention that comparison requires a column reference, got: "
                        + ex.getMessage());
        // Must NOT contain the misleading "Expected column reference but found" phrasing
        // that implies only one operand is wrong
        assertFalse(ex.getMessage().contains("Expected column reference but found"),
                "Error should NOT use misleading single-operand phrasing, got: " + ex.getMessage());
    }

    // @spec query.sql-query-support.R50,R58
    // Finding: F-R1.dispatch_routing.1.1
    // Bug: default branch in translateExpression switch on sealed Expression type
    // suppresses compile-time exhaustiveness checking — new subtypes are
    // silently caught at runtime instead of flagged at compile time
    // Correct behavior: each unhandled Expression subtype has an explicit case
    // with a descriptive error message; no default branch exists so the
    // compiler enforces exhaustiveness
    // Fix location: SqlTranslator.translateExpression (lines 131-145)
    // Regression watch: adding a new Expression subtype must cause a compile error
    // in translateExpression if no case is added for it
    @Test
    void test_translateExpression_sealedExhaustiveness_booleanLiteralHasSpecificError() {
        // WHERE TRUE is parsed as a bare BooleanLiteral — not wrapped in a Comparison.
        // With a default branch, this produces a generic "Unsupported expression type" error.
        // With explicit cases, the error should name the specific type.
        var ex = assertThrows(SqlParseException.class,
                () -> translate("SELECT * FROM test WHERE TRUE"));
        // After the fix, the error message should specifically mention BooleanLiteral
        // rather than the generic "Unsupported expression type" phrasing.
        assertTrue(ex.getMessage().contains("BooleanLiteral"),
                "Error should specifically name BooleanLiteral, got: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("Unsupported expression type"),
                "Error should NOT use generic 'Unsupported expression type' phrasing, got: "
                        + ex.getMessage());
    }

    // @spec query.sql-query-support.R94
    // Finding: F-R1.dispatch_routing.1.3
    // Bug: translateFunctionCall lacks specific VECTOR_DISTANCE case in WHERE context —
    // user sees generic "Unsupported function in WHERE clause: VECTOR_DISTANCE"
    // instead of actionable guidance that VECTOR_DISTANCE belongs in ORDER BY
    // Correct behavior: error message should indicate VECTOR_DISTANCE must appear in
    // ORDER BY, not WHERE
    // Fix location: SqlTranslator.translateFunctionCall (lines 247-248)
    // Regression watch: must not break VECTOR_DISTANCE in ORDER BY or MATCH in WHERE
    @Test
    void test_translateFunctionCall_vectorDistanceInWhere_errorGuidesToOrderBy() {
        // VECTOR_DISTANCE as a standalone predicate in WHERE — the parser returns a bare
        // FunctionCall node (no comparison wrapper), which translateExpression routes to
        // translateFunctionCall. Without a specific "VECTOR_DISTANCE" case, it falls to
        // default with a generic "Unsupported function in WHERE clause" error.
        var ex = assertThrows(SqlParseException.class, () -> translate(
                "SELECT * FROM test WHERE VECTOR_DISTANCE(embedding, ?, 'cosine')"));
        // The error should guide the user to use ORDER BY instead
        assertTrue(ex.getMessage().contains("ORDER BY"),
                "Error should mention ORDER BY as the correct location, got: " + ex.getMessage());
        // Should NOT use the generic "Unsupported function" phrasing
        assertFalse(ex.getMessage().contains("Unsupported function"),
                "Error should NOT use generic 'Unsupported function' phrasing, got: "
                        + ex.getMessage());
    }
}
