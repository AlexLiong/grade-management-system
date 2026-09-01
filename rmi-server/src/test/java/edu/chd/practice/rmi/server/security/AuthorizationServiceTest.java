package edu.chd.practice.rmi.server.security;

import edu.chd.practice.rmi.contract.RemoteServiceException;
import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.rmi.contract.dto.FilterOperator;
import edu.chd.practice.rmi.contract.dto.InvocationContext;
import edu.chd.practice.rmi.contract.dto.MutationCommand;
import edu.chd.practice.rmi.contract.dto.MutationType;
import edu.chd.practice.rmi.contract.dto.SelectRequest;
import edu.chd.practice.rmi.server.sql.SchemaRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizationServiceTest {
    @Test
    void gatewayCanReadOnlyNarrowAuthenticationMetadata() {
        JdbcTemplate jdbc = securityDatabase();
        jdbc.update("INSERT INTO users(id,username,status,student_id,teacher_id) VALUES"
                + "('gateway-user','web-backend','ACTIVE',NULL,NULL),"
                + "('teacher-user','teacher01','ACTIVE',NULL,'teacher-1')");
        jdbc.update("INSERT INTO roles(id,code) VALUES('gateway-role','GATEWAY')");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES('gateway-user','gateway-role')");
        jdbc.execute("CREATE TABLE teachers(id VARCHAR(64),user_id VARCHAR(64),org_id VARCHAR(64))");
        AuthorizationService service = new AuthorizationService(jdbc, new SchemaRegistry());
        InvocationContext context = context("web-backend", "GATEWAY");

        assertDoesNotThrow(() -> service.authorizeRead(request("users",
                List.of("id", "username", "password_hash", "display_name", "status", "student_id", "teacher_id"),
                Filter.of("username", FilterOperator.EQ, "teacher01"), 1), context));
        assertDoesNotThrow(() -> service.authorizeRead(request("users",
                List.of("id", "username", "password_hash", "display_name", "status", "student_id", "teacher_id"),
                Filter.of("id", FilterOperator.EQ, "teacher-user"), 1), context));
        assertDoesNotThrow(() -> service.authorizeRead(request("user_roles", List.of("role_id"),
                Filter.of("user_id", FilterOperator.EQ, "teacher-user"), 100), context));
        assertDoesNotThrow(() -> service.authorizeRead(request("roles", List.of("id", "code"),
                Filter.of("id", FilterOperator.IN, "teacher-role"), 100), context));
        assertDoesNotThrow(() -> service.authorizeRead(request("roles", List.of("id"),
                Filter.of("code", FilterOperator.IN, "TEACHER"), 100), context));
        assertDoesNotThrow(() -> service.authorizeRead(request("role_permissions", List.of("permission_id"),
                Filter.of("role_id", FilterOperator.IN, "teacher-role"), 500), context));
        assertDoesNotThrow(() -> service.authorizeRead(request("permissions", List.of("code"),
                Filter.of("id", FilterOperator.IN, "grade-read"), 500), context));
        assertDoesNotThrow(() -> service.authorizeRead(request("permissions", List.of("id", "code"),
                Filter.of("id", FilterOperator.IN, "grade-read"), 500), context));
        assertDoesNotThrow(() -> service.authorizeRead(request("user_permissions",
                List.of("permission_id", "granted"),
                Filter.of("user_id", FilterOperator.EQ, "teacher-user"), 500), context));
        assertDoesNotThrow(() -> service.authorizeRead(request("teachers", List.of("org_id"),
                Filter.of("id", FilterOperator.EQ, "teacher-1"), 1), context));

        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(select("courses"), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(request("teachers",
                List.of("id", "org_id"), Filter.of("id", FilterOperator.EQ, "teacher-1"), 1), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(request("teachers",
                List.of("org_id"), Filter.of("id", FilterOperator.IN, "teacher-1"), 1), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(request("users",
                List.of("id", "username", "password_hash", "display_name", "email", "status", "student_id",
                        "teacher_id"), Filter.of("username", FilterOperator.EQ, "teacher01"), 1), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(request("users",
                List.of("id", "username", "password_hash", "display_name", "status", "student_id", "teacher_id"),
                null, 1), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(request("roles", List.of("id"),
                Filter.of("code", FilterOperator.IN, "AUDITOR"), 100), context));
    }

    @Test
    void adminGradeReaderCanResolveGradeMetadataWithoutUserOrSchemeManagementAccess() {
        JdbcTemplate jdbc = securityDatabase();
        jdbc.update("INSERT INTO users(id,username,status,student_id,teacher_id) "
                + "VALUES('grade-reader','grade-reader','ACTIVE',NULL,NULL)");
        jdbc.update("INSERT INTO roles(id,code) VALUES('admin-grade-reader','ADMIN')");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES('grade-reader','admin-grade-reader')");
        jdbc.update("INSERT INTO permissions(id,code) VALUES('read-grades','GRADE_READ')");
        jdbc.update("INSERT INTO role_permissions(role_id,permission_id) "
                + "VALUES('admin-grade-reader','read-grades')");
        AuthorizationService service = new AuthorizationService(jdbc, new SchemaRegistry());
        InvocationContext context = context("grade-reader", "ADMIN");

        assertDoesNotThrow(() -> service.authorizeRead(select("grades"), context));
        assertDoesNotThrow(() -> service.authorizeRead(select("enrollments"), context));
        assertDoesNotThrow(() -> service.authorizeRead(select("students"), context));
        assertDoesNotThrow(() -> service.authorizeRead(select("courses"), context));
        assertDoesNotThrow(() -> service.authorizeRead(select("course_offerings"), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(select("users"), context));
        assertThrows(RemoteServiceException.class,
                () -> service.authorizeRead(select("grading_schemes"), context));
    }

    @Test
    void limitedUserManagerCanReadAssemblyDataButCannotAssignRolesOrWritePasswords() {
        JdbcTemplate jdbc = securityDatabase();
        jdbc.update("INSERT INTO users(id,username,status,student_id,teacher_id) VALUES('u1','limited','ACTIVE',NULL,NULL)");
        jdbc.update("INSERT INTO users(id,username,status,student_id,teacher_id) VALUES('u3','ordinary','ACTIVE',NULL,NULL)");
        jdbc.update("INSERT INTO roles(id,code) VALUES('r1','ADMIN')");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES('u1','r1')");
        jdbc.update("INSERT INTO permissions(id,code) VALUES('p1','USER_MANAGE'),('p2','PERMISSION_MANAGE')");
        jdbc.update("INSERT INTO role_permissions(role_id,permission_id) VALUES('r1','p1')");
        AuthorizationService service = new AuthorizationService(jdbc, new SchemaRegistry());
        InvocationContext context = context("limited", "ADMIN");

        assertDoesNotThrow(() -> service.authorizeRead(select("roles"), context));
        assertDoesNotThrow(() -> service.authorizeRead(select("orgs"), context));
        assertDoesNotThrow(() -> service.authorizeWrite(new MutationCommand(MutationType.UPDATE, "users",
                Map.of("display_name", "Ordinary User"), List.of(Filter.of("id", FilterOperator.EQ, "u3"))), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeWrite(new MutationCommand(
                MutationType.UPDATE, "users", Map.of("display_name", "Changed Admin"),
                List.of(Filter.of("id", FilterOperator.EQ, "u1"))), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeWrite(
                new MutationCommand(MutationType.INSERT, "user_roles",
                        Map.of("user_id", "u1", "role_id", "r1"), List.of()), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeWrite(
                new MutationCommand(MutationType.UPDATE, "users", Map.of("password_hash", "replacement"),
                        List.of(Filter.of("id", FilterOperator.EQ, "u1"))), context));
        assertDoesNotThrow(() -> service.authorizeWrite(
                new MutationCommand(MutationType.UPDATE, "users", Map.of("password_hash", "replacement"),
                        List.of(Filter.of("id", FilterOperator.EQ, "u3"))), context));
    }

    @Test
    void teacherExceptionAccessRequiresAnOwnedEnrollmentAndReversionRequestsAreAdminOnly() {
        JdbcTemplate jdbc = securityDatabase();
        jdbc.update("INSERT INTO users(id,username,status,student_id,teacher_id) VALUES('u2','teacher','ACTIVE',NULL,'t1')");
        jdbc.update("INSERT INTO roles(id,code) VALUES('r2','TEACHER')");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES('u2','r2')");
        jdbc.update("INSERT INTO permissions(id,code) VALUES('p3','GRADE_READ')");
        jdbc.update("INSERT INTO role_permissions(role_id,permission_id) VALUES('r2','p3')");
        jdbc.execute("CREATE TABLE course_offerings(id VARCHAR(64),teacher_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE enrollments(id VARCHAR(64),offering_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE grade_exceptions(id VARCHAR(64),enrollment_id VARCHAR(64))");
        jdbc.update("INSERT INTO course_offerings(id,teacher_id) VALUES('o1','t1'),('o2','t2')");
        jdbc.update("INSERT INTO enrollments(id,offering_id) VALUES('e1','o1'),('e2','o2')");
        jdbc.update("INSERT INTO grade_exceptions(id,enrollment_id) VALUES('x1','e1'),('x2','e2')");
        SchemaRegistry registry = new SchemaRegistry();
        AuthorizationService service = new AuthorizationService(jdbc, registry);
        InvocationContext context = context("teacher", "TEACHER");

        assertDoesNotThrow(() -> service.authorizeRead(select("grade_exceptions",
                Filter.of("enrollment_id", FilterOperator.EQ, "e1")), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(select("grade_exceptions",
                Filter.of("id", FilterOperator.EQ, "x2")), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(select("teacher_history_courses",
                Filter.of("teacher_id", FilterOperator.EQ, "t1")), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(select(
                "teacher_course_historical_grades", Filter.of("teacher_id", FilterOperator.EQ, "t1")), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeWrite(
                new MutationCommand(MutationType.INSERT, "grade_exceptions",
                        Map.of("id", "x3", "enrollment_id", "e2", "type", "OTHER",
                                "description", "test", "status", "OPEN", "reported_by", "teacher"), List.of()),
                context));
        assertFalse(registry.requireTable("reversion_requests").readRoles().contains("TEACHER"));
        assertFalse(registry.requireTable("reversion_requests").writeRoles().contains("TEACHER"));
        assertTrue(registry.requireTable("teacher_history_courses").writeRoles().isEmpty());
        assertTrue(registry.requireTable("teacher_course_historical_grades").writeRoles().isEmpty());
    }

    @Test
    void teacherWithDraftPermissionCanUseOnlyTheDedicatedMakeupClearEnvelope() {
        JdbcTemplate jdbc = securityDatabase();
        jdbc.update("INSERT INTO users(id,username,status,student_id,teacher_id) "
                + "VALUES('u-clear','clear-teacher','ACTIVE',NULL,'t-clear')");
        jdbc.update("INSERT INTO roles(id,code) VALUES('r-clear','TEACHER')");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES('u-clear','r-clear')");
        jdbc.update("INSERT INTO permissions(id,code) VALUES('p-clear','GRADE_DRAFT_WRITE')");
        jdbc.update("INSERT INTO role_permissions(role_id,permission_id) VALUES('r-clear','p-clear')");
        jdbc.execute("CREATE TABLE course_offerings(id VARCHAR(64),teacher_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE enrollments(id VARCHAR(64),offering_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE grades(id VARCHAR(64),enrollment_id VARCHAR(64))");
        jdbc.update("INSERT INTO course_offerings(id,teacher_id) VALUES('o-clear','t-clear')");
        jdbc.update("INSERT INTO enrollments(id,offering_id) VALUES('e-clear','o-clear')");
        jdbc.update("INSERT INTO grades(id,enrollment_id) VALUES('g-clear','e-clear')");
        AuthorizationService service = new AuthorizationService(jdbc, new SchemaRegistry());
        InvocationContext context = context("clear-teacher", "TEACHER");
        Map<String, String> values = Map.of(
                "score_ciphertext", "cipher", "score_nonce", "nonce", "score_integrity", "integrity",
                "status", "SUBMITTED", "version", "8", "updated_at", "2026-08-31T00:00:00Z");
        List<Filter> filters = List.of(
                Filter.of("id", FilterOperator.EQ, "g-clear"),
                Filter.of("version", FilterOperator.EQ, "7"),
                Filter.of("status", FilterOperator.EQ, "SUBMITTED"));

        assertDoesNotThrow(() -> service.authorizeWrite(new MutationCommand(
                MutationType.UPDATE, "grades", values, filters, "MAKEUP_CLEAR", null), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeWrite(new MutationCommand(
                MutationType.UPDATE, "grades", values, filters, "MAKEUP_WITHDRAW", null), context));
    }

    @Test
    void teacherGradeWritesRequireOneVersionAndAConsistentOfferingRelation() {
        JdbcTemplate jdbc = securityDatabase();
        jdbc.update("INSERT INTO users(id,username,status,student_id,teacher_id) "
                + "VALUES('u-draft','draft-teacher','ACTIVE',NULL,'t-draft')");
        jdbc.update("INSERT INTO roles(id,code) VALUES('r-draft','TEACHER')");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES('u-draft','r-draft')");
        jdbc.update("INSERT INTO permissions(id,code) VALUES('p-draft','GRADE_DRAFT_WRITE')");
        jdbc.update("INSERT INTO role_permissions(role_id,permission_id) VALUES('r-draft','p-draft')");
        jdbc.execute("CREATE TABLE course_offerings(id VARCHAR(64),teacher_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE enrollments(id VARCHAR(64),offering_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE grading_schemes(id VARCHAR(64),offering_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE grades(id VARCHAR(64),enrollment_id VARCHAR(64))");
        jdbc.update("INSERT INTO course_offerings(id,teacher_id) VALUES"
                + "('offering-a','t-draft'),('offering-b','t-draft'),('offering-other','t-other')");
        jdbc.update("INSERT INTO enrollments(id,offering_id) VALUES"
                + "('enrollment-a','offering-a'),('enrollment-other','offering-other')");
        jdbc.update("INSERT INTO grading_schemes(id,offering_id) VALUES"
                + "('scheme-a','offering-a'),('scheme-b','offering-b'),('scheme-other','offering-other')");
        jdbc.update("INSERT INTO grades(id,enrollment_id) VALUES('grade-a','enrollment-a')");
        AuthorizationService service = new AuthorizationService(jdbc, new SchemaRegistry());
        InvocationContext context = context("draft-teacher", "TEACHER");

        Map<String, String> insert = Map.of(
                "id", "grade-new", "enrollment_id", "enrollment-a", "scheme_id", "scheme-a",
                "score_ciphertext", "cipher", "score_nonce", "nonce", "score_integrity", "integrity",
                "status", "DRAFT", "version", "1");
        assertDoesNotThrow(() -> service.authorizeWrite(
                new MutationCommand(MutationType.INSERT, "grades", insert, List.of()), context));
        Map<String, String> mismatchedScheme = new java.util.HashMap<>(insert);
        mismatchedScheme.put("scheme_id", "scheme-b");
        assertThrows(RemoteServiceException.class, () -> service.authorizeWrite(
                new MutationCommand(MutationType.INSERT, "grades", mismatchedScheme, List.of()), context));
        Map<String, String> otherTeacher = new java.util.HashMap<>(insert);
        otherTeacher.put("enrollment_id", "enrollment-other");
        otherTeacher.put("scheme_id", "scheme-other");
        assertThrows(RemoteServiceException.class, () -> service.authorizeWrite(
                new MutationCommand(MutationType.INSERT, "grades", otherTeacher, List.of()), context));

        Map<String, String> update = Map.of("score_ciphertext", "changed", "version", "2", "status", "DRAFT");
        List<Filter> locked = List.of(
                Filter.of("id", FilterOperator.EQ, "grade-a"),
                Filter.of("version", FilterOperator.EQ, "1"),
                Filter.of("status", FilterOperator.EQ, "DRAFT"));
        assertDoesNotThrow(() -> service.authorizeWrite(
                new MutationCommand(MutationType.UPDATE, "grades", update, locked), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeWrite(
                new MutationCommand(MutationType.UPDATE, "grades", update, List.of(
                        Filter.of("id", FilterOperator.EQ, "grade-a"),
                        Filter.of("status", FilterOperator.EQ, "DRAFT"))), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeWrite(
                new MutationCommand(MutationType.UPDATE, "grades",
                        Map.of("score_ciphertext", "changed", "version", "3", "status", "DRAFT"),
                        locked), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeWrite(
                new MutationCommand(MutationType.DELETE, "grades", Map.of(), locked), context));
    }

    @Test
    void humanRolesAreExclusiveButInternalServiceRolesMayBeCombined() {
        JdbcTemplate jdbc = securityDatabase();
        jdbc.update("INSERT INTO users(id,username,status,student_id,teacher_id) VALUES"
                + "('manager','manager','ACTIVE',NULL,NULL),('person','person','ACTIVE',NULL,NULL),"
                + "('service','service','ACTIVE',NULL,NULL)");
        jdbc.update("INSERT INTO roles(id,code) VALUES('admin','ADMIN'),('teacher','TEACHER'),"
                + "('student','STUDENT'),('gateway','GATEWAY'),('system','SYSTEM')");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES"
                + "('manager','admin'),('person','teacher'),('service','gateway')");
        jdbc.update("INSERT INTO permissions(id,code) VALUES('manage-users','USER_MANAGE'),"
                + "('manage-permissions','PERMISSION_MANAGE')");
        jdbc.update("INSERT INTO role_permissions(role_id,permission_id) VALUES"
                + "('admin','manage-users'),('admin','manage-permissions')");
        AuthorizationService service = new AuthorizationService(jdbc, new SchemaRegistry());
        InvocationContext context = context("manager", "ADMIN");

        assertThrows(RemoteServiceException.class, () -> service.authorizeWrite(
                new MutationCommand(MutationType.INSERT, "user_roles",
                        Map.of("user_id", "person", "role_id", "student"), List.of()), context));
        assertDoesNotThrow(() -> service.authorizeWrite(
                new MutationCommand(MutationType.INSERT, "user_roles",
                        Map.of("user_id", "service", "role_id", "system"), List.of()), context));
    }

    @Test
    void historyOnlyTeacherCanReadSubmittedGradesFromClosedOwnedOfferingsOnly() {
        JdbcTemplate jdbc = securityDatabase();
        jdbc.update("INSERT INTO users(id,username,status,student_id,teacher_id) "
                + "VALUES('u-history','history-teacher','ACTIVE',NULL,'t-history')");
        jdbc.update("INSERT INTO roles(id,code) VALUES('r-history-teacher','TEACHER')");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES('u-history','r-history-teacher')");
        jdbc.update("INSERT INTO permissions(id,code) VALUES"
                + "('p-history','GRADE_HISTORY_READ'),('p-grade-read','GRADE_READ')");
        jdbc.update("INSERT INTO role_permissions(role_id,permission_id) "
                + "VALUES('r-history-teacher','p-history')");
        jdbc.update("INSERT INTO user_permissions(user_id,permission_id,granted) "
                + "VALUES('u-history','p-grade-read',FALSE)");
        jdbc.execute("CREATE TABLE course_offerings(id VARCHAR(64),course_id VARCHAR(64),"
                + "teacher_id VARCHAR(64),status VARCHAR(24))");
        jdbc.execute("CREATE TABLE courses(id VARCHAR(64))");
        jdbc.execute("CREATE TABLE enrollments(id VARCHAR(64),offering_id VARCHAR(64),student_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE grades(id VARCHAR(64),enrollment_id VARCHAR(64),status VARCHAR(24))");
        jdbc.execute("CREATE TABLE grading_schemes(id VARCHAR(64),offering_id VARCHAR(64))");
        jdbc.update("INSERT INTO courses(id) VALUES('closed-course'),('open-course'),('other-course')");
        jdbc.update("INSERT INTO course_offerings(id,course_id,teacher_id,status) VALUES"
                + "('closed-owned','closed-course','t-history','CLOSED'),"
                + "('open-owned','open-course','t-history','OPEN'),"
                + "('closed-other','other-course','t-other','CLOSED')");
        jdbc.update("INSERT INTO enrollments(id,offering_id,student_id) VALUES"
                + "('closed-enrollment','closed-owned','closed-student'),"
                + "('open-enrollment','open-owned','open-student'),"
                + "('other-enrollment','closed-other','other-student')");
        jdbc.update("INSERT INTO grading_schemes(id,offering_id) VALUES"
                + "('closed-scheme','closed-owned'),('open-scheme','open-owned')");
        jdbc.update("INSERT INTO grades(id,enrollment_id,status) VALUES"
                + "('closed-grade','closed-enrollment','SUBMITTED'),"
                + "('open-grade','open-enrollment','SUBMITTED')");
        AuthorizationService service = new AuthorizationService(jdbc, new SchemaRegistry());
        InvocationContext context = context("history-teacher", "TEACHER");

        assertDoesNotThrow(() -> service.authorizeRead(select("teachers",
                Filter.of("user_id", FilterOperator.EQ, "u-history")), context));
        assertDoesNotThrow(() -> service.authorizeRead(select("course_offerings",
                Filter.of("teacher_id", FilterOperator.EQ, "t-history"),
                Filter.of("status", FilterOperator.EQ, "CLOSED")), context));
        assertDoesNotThrow(() -> service.authorizeRead(select("teacher_history_courses",
                Filter.of("teacher_id", FilterOperator.EQ, "t-history")), context));
        assertDoesNotThrow(() -> service.authorizeRead(select("teacher_course_historical_grades",
                Filter.of("teacher_id", FilterOperator.EQ, "t-history")), context));
        assertDoesNotThrow(() -> service.authorizeRead(select("grading_schemes",
                Filter.of("offering_id", FilterOperator.EQ, "closed-owned")), context));
        assertDoesNotThrow(() -> service.authorizeRead(select("enrollments",
                Filter.of("offering_id", FilterOperator.EQ, "closed-owned")), context));
        assertDoesNotThrow(() -> service.authorizeRead(select("grades",
                Filter.of("enrollment_id", FilterOperator.EQ, "closed-enrollment"),
                Filter.of("status", FilterOperator.EQ, "SUBMITTED")), context));
        assertDoesNotThrow(() -> service.authorizeRead(select("students",
                Filter.of("id", FilterOperator.EQ, "closed-student")), context));
        assertDoesNotThrow(() -> service.authorizeRead(select("courses",
                Filter.of("id", FilterOperator.EQ, "closed-course")), context));
        assertDoesNotThrow(() -> service.authorizeRead(select("grade_history",
                Filter.of("grade_id", FilterOperator.EQ, "closed-grade")), context));

        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(select("course_offerings",
                Filter.of("teacher_id", FilterOperator.EQ, "t-history"),
                Filter.of("status", FilterOperator.EQ, "OPEN")), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(
                select("teacher_history_courses"), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(
                select("teacher_course_historical_grades"), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(select("teacher_history_courses",
                Filter.of("teacher_id", FilterOperator.EQ, "t-other")), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(select(
                "teacher_course_historical_grades", Filter.of("teacher_id", FilterOperator.EQ, "t-other")),
                context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(select("grading_schemes",
                Filter.of("offering_id", FilterOperator.EQ, "open-owned")), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(select("grades",
                Filter.of("enrollment_id", FilterOperator.EQ, "open-enrollment"),
                Filter.of("status", FilterOperator.EQ, "SUBMITTED")), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(select("students",
                Filter.of("id", FilterOperator.EQ, "other-student")), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(select("courses",
                Filter.of("id", FilterOperator.EQ, "open-course")), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(select("courses",
                Filter.of("id", FilterOperator.EQ, "other-course")), context));
        assertThrows(RemoteServiceException.class, () -> service.authorizeRead(select("grade_history",
                Filter.of("grade_id", FilterOperator.EQ, "open-grade")), context));
    }

    private static JdbcTemplate securityDatabase() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:auth-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE users(id VARCHAR(64),username VARCHAR(64),status VARCHAR(24),"
                + "student_id VARCHAR(64),teacher_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE roles(id VARCHAR(64),code VARCHAR(64))");
        jdbc.execute("CREATE TABLE user_roles(user_id VARCHAR(64),role_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE permissions(id VARCHAR(64),code VARCHAR(96))");
        jdbc.execute("CREATE TABLE role_permissions(role_id VARCHAR(64),permission_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE user_permissions(user_id VARCHAR(64),permission_id VARCHAR(64),granted BOOLEAN)");
        return jdbc;
    }

    private static InvocationContext context(String principal, String role) {
        return new InvocationContext("request", principal, List.of(role), 1L, "nonce", "signature");
    }

    private static SelectRequest select(String table, Filter... filters) {
        return new SelectRequest(table, List.of("id"), List.of(filters), List.of(), 0, 20);
    }

    private static SelectRequest request(String table, List<String> columns, Filter filter, int pageSize) {
        return new SelectRequest(table, columns, filter == null ? List.of() : List.of(filter), List.of(), 0, pageSize);
    }
}
