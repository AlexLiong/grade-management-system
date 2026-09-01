package edu.chd.practice.rmi.server.sql;

import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.rmi.contract.dto.FilterOperator;
import edu.chd.practice.rmi.contract.dto.SelectRequest;
import edu.chd.practice.rmi.server.config.SecurityProperties;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeSqlBuilderTest {
    @Test
    void likePredicateExecutesOnH2WithoutInvalidEscapeLiteral() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:like-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE audit_logs (id VARCHAR(64), operation VARCHAR(64))");
        jdbc.update("INSERT INTO audit_logs(id,operation) VALUES(?,?)", "a-1", "GRADE_SUBMIT");
        SafeSqlBuilder builder = new SafeSqlBuilder(new SchemaRegistry(), new SecurityProperties());
        SelectRequest request = new SelectRequest("audit_logs", List.of("id"),
                List.of(Filter.of("operation", FilterOperator.LIKE, "%GRADE%")), List.of(), 0, 20);

        PreparedSql prepared = builder.count(request);

        assertFalse(prepared.sql().contains("ESCAPE"));
        assertEquals(1L, new JdbcPreparedExecutor(jdbc).queryCount(prepared));
    }

    @Test
    void containsPredicateEscapesUserWildcardsAndTheEscapeCharacter() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:contains-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE audit_logs (id VARCHAR(64), operation VARCHAR(64))");
        jdbc.update("INSERT INTO audit_logs(id,operation) VALUES(?,?),(?,?)",
                "literal", "PREFIX_GRADE_%!_SUFFIX", "wildcard", "GRADE_AX_SUFFIX");
        SafeSqlBuilder builder = new SafeSqlBuilder(new SchemaRegistry(), new SecurityProperties());
        SelectRequest request = new SelectRequest("audit_logs", List.of("id"),
                List.of(Filter.of("operation", FilterOperator.CONTAINS, "GRADE_%!")), List.of(), 0, 20);

        PreparedSql prepared = builder.count(request);

        assertTrue(prepared.sql().contains("LIKE ? ESCAPE '!'"));
        assertEquals(List.of("%GRADE!_!%!!%"), prepared.parameters());
        assertEquals(1L, new JdbcPreparedExecutor(jdbc).queryCount(prepared));
    }
}
