/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.oracle;

import com.google.common.collect.ImmutableList;
import io.trino.Session;
import io.trino.execution.QueryInfo;
import io.trino.plugin.jdbc.BaseJdbcConnectorTest;
import io.trino.sql.planner.plan.AggregationNode;
import io.trino.sql.planner.plan.FilterNode;
import io.trino.sql.planner.plan.ProjectNode;
import io.trino.testing.MaterializedResult;
import io.trino.testing.ResultWithQueryId;
import io.trino.testing.TestingConnectorBehavior;
import io.trino.testing.sql.TestTable;
import org.testng.annotations.Test;

import java.util.Optional;

import static io.trino.plugin.oracle.TestingOracleServer.TEST_USER;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.testing.MaterializedResult.resultBuilder;
import static io.trino.testing.assertions.Assert.assertEquals;
import static io.trino.testing.sql.TestTable.randomTableSuffix;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public abstract class BaseOracleConnectorTest
        extends BaseJdbcConnectorTest
{
    @Override
    protected boolean hasBehavior(TestingConnectorBehavior connectorBehavior)
    {
        switch (connectorBehavior) {
            case SUPPORTS_LIMIT_PUSHDOWN:
                return false;

            case SUPPORTS_TOPN_PUSHDOWN:
                return false;

            case SUPPORTS_AGGREGATION_PUSHDOWN:
                return false;

            case SUPPORTS_JOIN_PUSHDOWN:
                return true;

            case SUPPORTS_JOIN_PUSHDOWN_WITH_DISTINCT_FROM:
                return false;

            case SUPPORTS_COMMENT_ON_TABLE:
                return false;

            case SUPPORTS_ARRAY:
                return false;

            case SUPPORTS_CREATE_SCHEMA:
                return false;

            case SUPPORTS_RENAME_TABLE_ACROSS_SCHEMAS:
                return false;

            default:
                return super.hasBehavior(connectorBehavior);
        }
    }

    @Override
    public void testCreateSchema()
    {
        // schema creation is not supported
        assertQueryFails("CREATE SCHEMA test_schema_create", "This connector does not support creating schemas");
    }

    @Override
    protected String dataMappingTableName(String trinoTypeName)
    {
        return "tmp_trino_" + System.nanoTime();
    }

    @Override
    protected Optional<DataMappingTestSetup> filterDataMappingSmokeTestData(DataMappingTestSetup dataMappingTestSetup)
    {
        String typeName = dataMappingTestSetup.getTrinoTypeName();
        if (typeName.equals("time")) {
            return Optional.empty();
        }
        if (typeName.equals("boolean")) {
            // Oracle does not have native support for boolean however usually it is represented as number(1)
            return Optional.empty();
        }

        return Optional.of(dataMappingTestSetup);
    }

    @Override
    protected TestTable createTableWithDefaultColumns()
    {
        return new TestTable(
                onRemoteDatabase(),
                "test_default_columns",
                "(col_required decimal(20,0) NOT NULL," +
                        "col_nullable decimal(20,0)," +
                        "col_default decimal(20,0) DEFAULT 43," +
                        "col_nonnull_default decimal(20,0) DEFAULT 42 NOT NULL ," +
                        "col_required2 decimal(20,0) NOT NULL)");
    }

    @Override
    protected TestTable createTableWithUnsupportedColumn()
    {
        return new TestTable(
                onRemoteDatabase(),
                "test_unsupported_col",
                "(one NUMBER(19), two NUMBER, three VARCHAR2(10 CHAR))");
    }

    @Test
    @Override
    public void testSymbolAliasing()
    {
        // Replace tablename to less than 30chars, max size naming on oracle
        String tableName = "symbol_aliasing" + System.currentTimeMillis();
        assertUpdate("CREATE TABLE " + tableName + " AS SELECT 1 foo_1, 2 foo_2_4", 1);
        assertQuery("SELECT foo_1, foo_2_4 FROM " + tableName, "SELECT 1, 2");
        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    @Override
    public void testRenameColumn()
    {
        // Replace tablename to less than 30chars, max size naming on oracle
        String tableName = "test_renamecol_" + System.currentTimeMillis();
        assertUpdate("CREATE TABLE " + tableName + " AS SELECT 'some value' x", 1);

        assertUpdate("ALTER TABLE " + tableName + " RENAME COLUMN x TO y");
        assertQuery("SELECT y FROM " + tableName, "VALUES 'some value'");

        assertUpdate("ALTER TABLE " + tableName + " RENAME COLUMN y TO Z"); // 'Z' is upper-case, not delimited
        assertQuery(
                "SELECT z FROM " + tableName, // 'z' is lower-case, not delimited
                "VALUES 'some value'");

        // There should be exactly one column
        assertQuery("SELECT * FROM " + tableName, "VALUES 'some value'");

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    @Override
    public void testWrittenStats()
    {
        // Replace tablename to fetch max size naming on oracle
        String tableName = "written_stats_" + System.currentTimeMillis();
        String sql = "CREATE TABLE " + tableName + " AS SELECT * FROM nation";
        ResultWithQueryId<MaterializedResult> resultResultWithQueryId = getDistributedQueryRunner().executeWithQueryId(getSession(), sql);
        QueryInfo queryInfo = getDistributedQueryRunner().getCoordinator().getQueryManager().getFullQueryInfo(resultResultWithQueryId.getQueryId());

        assertEquals(queryInfo.getQueryStats().getOutputPositions(), 1L);
        assertEquals(queryInfo.getQueryStats().getWrittenPositions(), 25L);
        assertTrue(queryInfo.getQueryStats().getLogicalWrittenDataSize().toBytes() > 0L);

        sql = "INSERT INTO " + tableName + " SELECT * FROM nation LIMIT 10";
        resultResultWithQueryId = getDistributedQueryRunner().executeWithQueryId(getSession(), sql);
        queryInfo = getDistributedQueryRunner().getCoordinator().getQueryManager().getFullQueryInfo(resultResultWithQueryId.getQueryId());

        assertEquals(queryInfo.getQueryStats().getOutputPositions(), 1L);
        assertEquals(queryInfo.getQueryStats().getWrittenPositions(), 10L);
        assertTrue(queryInfo.getQueryStats().getLogicalWrittenDataSize().toBytes() > 0L);

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    @Override
    public void testShowColumns()
    {
        MaterializedResult actual = computeActual("SHOW COLUMNS FROM orders");
        MaterializedResult expected = resultBuilder(getSession(), VARCHAR, VARCHAR, VARCHAR, VARCHAR)
                .row("orderkey", "decimal(19,0)", "", "")
                .row("custkey", "decimal(19,0)", "", "")
                .row("orderstatus", "varchar(1)", "", "")
                .row("totalprice", "double", "", "")
                .row("orderdate", "timestamp(3)", "", "")
                .row("orderpriority", "varchar(15)", "", "")
                .row("clerk", "varchar(15)", "", "")
                .row("shippriority", "decimal(10,0)", "", "")
                .row("comment", "varchar(79)", "", "")
                .build();
        assertEquals(actual, expected);
    }

    /**
     * Test showing that column comment cannot be read. See {@link TestOraclePoolRemarksReportingConnectorSmokeTest#testCommentColumn()} for test
     * showing this works, when enabled.
     */
    @Test
    @Override
    public void testCommentColumn()
    {
        String tableName = "test_comment_column_" + randomTableSuffix();

        assertUpdate("CREATE TABLE " + tableName + "(a integer)");

        // comment set
        assertUpdate("COMMENT ON COLUMN " + tableName + ".a IS 'new comment'");
        // without remarksReporting Oracle does not return comments set
        assertThat((String) computeActual("SHOW CREATE TABLE " + tableName).getOnlyValue()).doesNotContain("COMMENT 'new comment'");
    }

    @Override
    public void testInformationSchemaFiltering()
    {
        assertQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_name = 'orders' LIMIT 1",
                "SELECT 'orders' table_name");
        assertQuery(
                "SELECT table_name FROM information_schema.columns WHERE data_type = 'decimal(19,0)' AND table_name = 'customer' AND column_name = 'custkey' LIMIT 1",
                "SELECT 'customer' table_name");
    }

    @Override
    protected boolean isColumnNameRejected(Exception exception, String columnName, boolean delimited)
    {
        if (columnName.equals("a\"quote") && exception.getMessage().contains("ORA-03001: unimplemented feature")) {
            return true;
        }

        return false;
    }

    @Test
    @Override
    public void testDescribeTable()
    {
        MaterializedResult expectedColumns = resultBuilder(getSession(), VARCHAR, VARCHAR, VARCHAR, VARCHAR)
                .row("orderkey", "decimal(19,0)", "", "")
                .row("custkey", "decimal(19,0)", "", "")
                .row("orderstatus", "varchar(1)", "", "")
                .row("totalprice", "double", "", "")
                .row("orderdate", "timestamp(3)", "", "")
                .row("orderpriority", "varchar(15)", "", "")
                .row("clerk", "varchar(15)", "", "")
                .row("shippriority", "decimal(10,0)", "", "")
                .row("comment", "varchar(79)", "", "")
                .build();
        MaterializedResult actualColumns = computeActual("DESCRIBE orders");
        assertEquals(actualColumns, expectedColumns);
    }

    @Test
    @Override
    public void testShowCreateTable()
    {
        assertThat((String) computeActual("SHOW CREATE TABLE orders").getOnlyValue())
                // If the connector reports additional column properties, the expected value needs to be adjusted in the test subclass
                .matches("CREATE TABLE \\w+\\.\\w+\\.orders \\Q(\n" +
                        "   orderkey decimal(19, 0),\n" +
                        "   custkey decimal(19, 0),\n" +
                        "   orderstatus varchar(1),\n" +
                        "   totalprice double,\n" +
                        "   orderdate timestamp(3),\n" +
                        "   orderpriority varchar(15),\n" +
                        "   clerk varchar(15),\n" +
                        "   shippriority decimal(10, 0),\n" +
                        "   comment varchar(79)\n" +
                        ")");
    }

    @Override
    public void testAggregationWithUnsupportedResultType()
    {
        // Overridden because for approx_set(bigint) a ProjectNode is present above table scan because Oracle doesn't support bigint
        // array_agg returns array, which is not supported
        assertThat(query("SELECT array_agg(nationkey) FROM nation"))
                .skipResultsCorrectnessCheckForPushdown() // array_agg doesn't have a deterministic order of elements in result array
                .isNotFullyPushedDown(AggregationNode.class);
        // histogram returns map, which is not supported
        assertThat(query("SELECT histogram(regionkey) FROM nation")).isNotFullyPushedDown(AggregationNode.class);
        // multimap_agg returns multimap, which is not supported
        assertThat(query("SELECT multimap_agg(regionkey, nationkey) FROM nation")).isNotFullyPushedDown(AggregationNode.class);
        // approx_set returns HyperLogLog, which is not supported
        assertThat(query("SELECT approx_set(nationkey) FROM nation")).isNotFullyPushedDown(AggregationNode.class, ProjectNode.class);
    }

    @Test
    public void testDropTable()
    {
        String tableName = "test_drop" + randomTableSuffix();
        assertUpdate(format("CREATE TABLE %s AS SELECT 1 test_drop", tableName), 1);
        assertTrue(getQueryRunner().tableExists(getSession(), tableName));

        assertUpdate("DROP TABLE " + tableName);
        assertFalse(getQueryRunner().tableExists(getSession(), tableName));
    }

    @Test
    public void testViews()
    {
        try (TestView view = new TestView(onRemoteDatabase(), getUser() + ".test_view", "AS SELECT 'O' as status FROM dual")) {
            assertQuery("SELECT status FROM " + view.getName(), "SELECT 'O'");
        }
    }

    @Test
    public void testSynonyms()
    {
        try (TestSynonym synonym = new TestSynonym(onRemoteDatabase(), getUser() + ".test_synonym", "FOR ORDERS")) {
            assertQueryFails("SELECT orderkey FROM " + synonym.getName(), "line 1:22: Table 'oracle.*' does not exist");
        }
    }

    @Test
    public void testPredicatePushdown()
    {
        // varchar equality
        assertThat(query("SELECT regionkey, nationkey, name FROM nation WHERE name = 'ROMANIA'"))
                .matches("VALUES (CAST(3 AS DECIMAL(19,0)), CAST(19 AS DECIMAL(19,0)), CAST('ROMANIA' AS varchar(25)))")
                .isFullyPushedDown();

        // varchar range
        assertThat(query("SELECT regionkey, nationkey, name FROM nation WHERE name BETWEEN 'POLAND' AND 'RPA'"))
                .matches("VALUES (CAST(3 AS DECIMAL(19,0)), CAST(19 AS DECIMAL(19,0)), CAST('ROMANIA' AS varchar(25)))")
                .isFullyPushedDown();

        // varchar different case
        assertThat(query("SELECT regionkey, nationkey, name FROM nation WHERE name = 'romania'"))
                .returnsEmptyResult()
                .isFullyPushedDown();

        // date equality
        assertThat(query("SELECT orderkey FROM orders WHERE orderdate = DATE '1992-09-29'"))
                .matches("VALUES CAST(1250 AS DECIMAL(19,0)), 34406, 38436, 57570")
                .isFullyPushedDown();

        // predicate over aggregation key (likely to be optimized before being pushed down into the connector)
        assertThat(query("SELECT * FROM (SELECT regionkey, sum(nationkey) FROM nation GROUP BY regionkey) WHERE regionkey = 3"))
                .matches("VALUES (CAST(3 AS decimal(19,0)), CAST(77 AS decimal(38,0)))")
                .isNotFullyPushedDown(AggregationNode.class, ProjectNode.class);

        // predicate over aggregation result
        assertThat(query("SELECT regionkey, sum(nationkey) FROM nation GROUP BY regionkey HAVING sum(nationkey) = 77"))
                .matches("VALUES (CAST(3 AS decimal(19,0)), CAST(77 AS decimal(38,0)))")
                .isNotFullyPushedDown(AggregationNode.class, ProjectNode.class);
    }

    @Test
    public void testPredicatePushdownForNumerics()
    {
        predicatePushdownTest("DECIMAL(9, 3)", "123.321", "<=", "124");
        predicatePushdownTest("DECIMAL(9, 3)", "123.321", "<=", "123.321");
        predicatePushdownTest("DECIMAL(9, 3)", "123.321", "=", "123.321");
        predicatePushdownTest("DECIMAL(30, 10)", "123456789.987654321", "<=", "123456790");
        predicatePushdownTest("DECIMAL(30, 10)", "123456789.987654321", "<=", "123456789.987654321");
        predicatePushdownTest("DECIMAL(30, 10)", "123456789.987654321", "=", "123456789.987654321");
        predicatePushdownTest("FLOAT(63)", "123456789.987654321", "<=", "CAST(123456789.99 AS REAL)");
        predicatePushdownTest("FLOAT(63)", "123456789.987654321", "<=", "CAST(123456789.99 AS DOUBLE)");
        predicatePushdownTest("FLOAT(126)", "123456789.987654321", "<=", "CAST(123456789.99 AS REAL)");
        predicatePushdownTest("FLOAT(126)", "123456789.987654321", "<=", "CAST(123456789.99 AS DOUBLE)");
        predicatePushdownTest("BINARY_FLOAT", "5.0f", "=", "CAST(5.0 AS REAL)");
        predicatePushdownTest("BINARY_DOUBLE", "20.233", "=", "CAST(20.233 AS DOUBLE)");
        predicatePushdownTest("NUMBER(5,3)", "5.0", "=", "CAST(5.0 AS DECIMAL(5,3))");
    }

    @Test
    public void testPredicatePushdownForChars()
    {
        predicatePushdownTest("CHAR(1)", "'0'", "=", "'0'");
        predicatePushdownTest("CHAR(1)", "'0'", "<=", "'0'");
        predicatePushdownTest("CHAR(5)", "'0'", "=", "CHAR'0'");
        predicatePushdownTest("CHAR(7)", "'my_char'", "=", "CAST('my_char' AS CHAR(7))");
        predicatePushdownTest("NCHAR(7)", "'my_char'", "=", "CAST('my_char' AS CHAR(7))");
        predicatePushdownTest("VARCHAR2(7)", "'my_char'", "=", "CAST('my_char' AS VARCHAR(7))");
        predicatePushdownTest("NVARCHAR2(7)", "'my_char'", "=", "CAST('my_char' AS VARCHAR(7))");

        try (TestTable table = new TestTable(
                onRemoteDatabase(),
                getUser() + ".test_pdown_",
                "(c_clob CLOB, c_nclob NCLOB)",
                ImmutableList.of("'my_clob', 'my_nclob'"))) {
            assertThat(query(format("SELECT c_clob FROM %s WHERE c_clob = cast('my_clob' as varchar)", table.getName()))).isNotFullyPushedDown(FilterNode.class);
            assertThat(query(format("SELECT c_nclob FROM %s WHERE c_nclob = cast('my_nclob' as varchar)", table.getName()))).isNotFullyPushedDown(FilterNode.class);
        }
    }

    @Test
    public void testTooLargeDomainCompactionThreshold()
    {
        assertQueryFails(
                Session.builder(getSession())
                        .setCatalogSessionProperty("oracle", "domain_compaction_threshold", "10000")
                        .build(),
                "SELECT * from nation", "Domain compaction threshold \\(10000\\) cannot exceed 1000");
    }

    private void predicatePushdownTest(String oracleType, String oracleLiteral, String operator, String filterLiteral)
    {
        String tableName = "test_pdown_" + oracleType.replaceAll("[^a-zA-Z0-9]", "");
        try (TestTable table = new TestTable(onRemoteDatabase(), getUser() + "." + tableName, format("(c %s)", oracleType))) {
            onRemoteDatabase().execute(format("INSERT INTO %s VALUES (%s)", table.getName(), oracleLiteral));

            assertThat(query(format("SELECT * FROM %s WHERE c %s %s", table.getName(), operator, filterLiteral)))
                    .isFullyPushedDown();
        }
    }

    protected String getUser()
    {
        return TEST_USER;
    }
}
