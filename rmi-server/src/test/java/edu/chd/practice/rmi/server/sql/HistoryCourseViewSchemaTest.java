package edu.chd.practice.rmi.server.sql;

import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.rmi.contract.dto.FilterOperator;
import edu.chd.practice.rmi.contract.dto.SelectRequest;
import edu.chd.practice.rmi.server.config.SecurityProperties;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HistoryCourseViewSchemaTest {
    @Test
    void viewExposesOnlyClosedOfferingsAndSupportsLiteralKeywordFiltering() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:history-view-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO orgs(id,code,name) VALUES('org','CS','计算机学院')");
        jdbc.update("INSERT INTO teachers(id,teacher_no,name,org_id,status) VALUES"
                + "('teacher-1','T001','教师一','org','ACTIVE'),('teacher-2','T002','教师二','org','ACTIVE')");
        jdbc.update("INSERT INTO courses(id,course_code,name,credit,hours,org_id,status) VALUES"
                + "('course-1','CS_100%','程序设计',3,48,'org','ACTIVE')");
        jdbc.update("INSERT INTO course_offerings"
                + "(id,course_id,teacher_id,academic_year,semester,class_name,capacity,status) VALUES"
                + "('closed-owned','course-1','teacher-1','2025-2026',2,'一班',30,'CLOSED'),"
                + "('open-owned','course-1','teacher-1','2025-2026',2,'二班',30,'OPEN'),"
                + "('closed-other','course-1','teacher-2','2025-2026',2,'三班',30,'CLOSED')");
        SafeSqlBuilder builder = new SafeSqlBuilder(new SchemaRegistry(), new SecurityProperties());
        List<Filter> filters = List.of(
                Filter.of("teacher_id", FilterOperator.EQ, "teacher-1"),
                Filter.of("search_text", FilterOperator.CONTAINS, "cs_100%"));
        SelectRequest request = new SelectRequest("teacher_history_courses", List.of("id"),
                filters, List.of(), 0, 20);
        JdbcPreparedExecutor executor = new JdbcPreparedExecutor(jdbc);

        assertEquals(1L, executor.queryCount(builder.count(request)));
        String[][] rows = executor.queryStrings(builder.select(request, DatabaseDialect.H2));
        assertEquals(1, rows.length);
        assertArrayEquals(new String[]{"closed-owned"}, rows[0]);
    }

    @Test
    void historicalGradeViewCountsOnlySubmittedGradesFromClosedOwnedOfferings() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:historical-grade-view-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO orgs(id,code,name) VALUES('org','CS','计算机学院')");
        jdbc.update("INSERT INTO teachers(id,teacher_no,name,org_id,status) VALUES"
                + "('teacher-1','T001','教师一','org','ACTIVE'),('teacher-2','T002','教师二','org','ACTIVE')");
        jdbc.update("INSERT INTO students(id,student_no,name,admission_year,class_name,major,status) VALUES"
                + "('student-1','20260001','学生甲',2026,'一班','计算机','ACTIVE'),"
                + "('student-2','20260002','学生乙',2026,'一班','计算机','ACTIVE')");
        jdbc.update("INSERT INTO courses(id,course_code,name,credit,hours,org_id,status) VALUES"
                + "('course-1','CS101','程序设计',3,48,'org','ACTIVE')");
        jdbc.update("INSERT INTO course_offerings"
                + "(id,course_id,teacher_id,academic_year,semester,class_name,capacity,status) VALUES"
                + "('closed-owned','course-1','teacher-1','2025-2026',2,'一班',30,'CLOSED'),"
                + "('open-owned','course-1','teacher-1','2026-2027',1,'一班',30,'OPEN'),"
                + "('closed-other','course-1','teacher-2','2025-2026',2,'二班',30,'CLOSED')");
        jdbc.update("INSERT INTO enrollments(id,offering_id,student_id,status) VALUES"
                + "('enrollment-closed','closed-owned','student-1','ENROLLED'),"
                + "('enrollment-draft','closed-owned','student-2','ENROLLED'),"
                + "('enrollment-open','open-owned','student-1','ENROLLED'),"
                + "('enrollment-other','closed-other','student-1','ENROLLED')");
        jdbc.update("INSERT INTO grading_schemes(id,offering_id,name,total_weight,version,status) VALUES"
                + "('scheme-closed','closed-owned','方案',100,1,'ACTIVE'),"
                + "('scheme-open','open-owned','方案',100,1,'ACTIVE'),"
                + "('scheme-other','closed-other','方案',100,1,'ACTIVE')");
        jdbc.update("INSERT INTO grades(id,enrollment_id,scheme_id,score_ciphertext,score_nonce,score_integrity,"
                + "status,version) VALUES"
                + "('submitted-closed','enrollment-closed','scheme-closed','c','n','i','SUBMITTED',1),"
                + "('draft-closed','enrollment-draft','scheme-closed','c','n','i','DRAFT',1),"
                + "('submitted-open','enrollment-open','scheme-open','c','n','i','SUBMITTED',1),"
                + "('submitted-other','enrollment-other','scheme-other','c','n','i','SUBMITTED',1)");
        SafeSqlBuilder builder = new SafeSqlBuilder(new SchemaRegistry(), new SecurityProperties());
        SelectRequest request = new SelectRequest("teacher_course_historical_grades", List.of("id"),
                List.of(Filter.of("teacher_id", FilterOperator.EQ, "teacher-1"),
                        Filter.of("course_id", FilterOperator.EQ, "course-1")), List.of(), 0, 20);
        JdbcPreparedExecutor executor = new JdbcPreparedExecutor(jdbc);

        assertEquals(1L, executor.queryCount(builder.count(request)));
        assertArrayEquals(new String[]{"submitted-closed"},
                executor.queryStrings(builder.select(request, DatabaseDialect.H2))[0]);
    }
}
