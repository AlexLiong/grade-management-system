package edu.chd.practice.rmi.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.chd.practice.rmi.contract.CanonicalForms;
import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.rmi.contract.dto.FilterOperator;
import edu.chd.practice.rmi.contract.dto.IdempotencyProbe;
import edu.chd.practice.rmi.contract.dto.InvocationContext;
import edu.chd.practice.rmi.contract.dto.MutationCommand;
import edu.chd.practice.rmi.contract.dto.MutationType;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManipulationRemoteServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void acceptsOnlyTheDefinedMakeupTransitions() {
        ObjectNode regular = payload(55, null, null, null, null);
        ObjectNode draft = payload(55, 88, 60, 60, "DRAFT");
        ObjectNode clearedDraft = payload(55, null, null, 55, "DRAFT");
        ObjectNode submitted = payload(55, 88, 60, 60, "SUBMITTED");

        assertDoesNotThrow(() -> ManipulationRemoteService.validateMakeupTransition(
                "MAKEUP_DRAFT", regular, draft));
        assertDoesNotThrow(() -> ManipulationRemoteService.validateMakeupTransition(
                "MAKEUP_SUBMIT", draft, submitted));
        assertDoesNotThrow(() -> ManipulationRemoteService.validateMakeupTransition(
                "MAKEUP_WITHDRAW", submitted, draft));
        assertDoesNotThrow(() -> ManipulationRemoteService.validateMakeupTransition(
                "MAKEUP_CLEAR", draft, clearedDraft));
    }

    @Test
    void rejectsRegularScoreOrComponentTamperingAndIneligibleMakeup() {
        ObjectNode before = payload(55, null, null, null, null);
        ObjectNode changedComponent = payload(55, 80, 60, 60, "DRAFT");
        ((ObjectNode) changedComponent.get("componentScores")).put("FINAL", 99);
        ObjectNode changedRegular = payload(54, 80, 60, 60, "DRAFT");
        ObjectNode passing = payload(60, null, null, null, null);
        ObjectNode passingDraft = payload(60, 80, 60, 60, "DRAFT");
        ObjectNode submitted = payload(55, 80, 60, 60, "SUBMITTED");
        ObjectNode cleared = payload(55, null, null, 55, "DRAFT");
        ObjectNode incompleteClear = payload(55, null, 60, 55, "DRAFT");

        assertThrows(IllegalArgumentException.class, () ->
                ManipulationRemoteService.validateMakeupTransition("MAKEUP_DRAFT", before, changedComponent));
        assertThrows(IllegalArgumentException.class, () ->
                ManipulationRemoteService.validateMakeupTransition("MAKEUP_DRAFT", before, changedRegular));
        assertThrows(IllegalArgumentException.class, () ->
                ManipulationRemoteService.validateMakeupTransition("MAKEUP_DRAFT", passing, passingDraft));
        assertThrows(IllegalArgumentException.class, () ->
                ManipulationRemoteService.validateMakeupTransition("MAKEUP_CLEAR", submitted, cleared));
        assertThrows(IllegalArgumentException.class, () ->
                ManipulationRemoteService.validateMakeupTransition("MAKEUP_CLEAR", passingDraft, incompleteClear));
    }

    @Test
    void everyGradeDeleteRequiresLargeApprovalAndApprovalTargetIsExact() {
        MutationCommand draftDelete = new MutationCommand(MutationType.DELETE, "grades", Map.of(),
                List.of(Filter.of("status", FilterOperator.EQ, "DRAFT")));
        MutationCommand otherDelete = new MutationCommand(MutationType.DELETE, "students", Map.of(), List.of());
        String expected = CanonicalForms.collection(draftDelete.getFilters());

        assertTrue(ManipulationRemoteService.isLargeGradeDeletion(draftDelete));
        assertFalse(ManipulationRemoteService.isLargeGradeDeletion(otherDelete));
        assertTrue(ManipulationRemoteService.isValidLargeApproval("requester", "approver", expected, expected));
        assertFalse(ManipulationRemoteService.isValidLargeApproval("requester", "requester", expected, expected));
        assertFalse(ManipulationRemoteService.isValidLargeApproval("requester", "approver", "*", expected));
    }

    @Test
    void makeupLedgerUsesReasonSpecificEventsAndTheCorrectSnapshotDirection() {
        MutationCommand draft = makeupCommand("MAKEUP_DRAFT", "SUBMITTED");
        MutationCommand clear = makeupCommand("MAKEUP_CLEAR", "SUBMITTED");
        MutationCommand submit = makeupCommand("MAKEUP_SUBMIT", "SUBMITTED");
        MutationCommand withdraw = makeupCommand("MAKEUP_WITHDRAW", "SUBMITTED");
        List<Map<String, String>> before = List.of(Map.of("status", "SUBMITTED"));

        assertEquals("GRADE_MAKEUP_DRAFT", ManipulationRemoteService.eventType(draft, before));
        assertEquals("GRADE_MAKEUP_CLEAR", ManipulationRemoteService.eventType(clear, before));
        assertEquals("GRADE_MAKEUP_SUBMIT", ManipulationRemoteService.eventType(submit, before));
        assertEquals("GRADE_MAKEUP_WITHDRAW", ManipulationRemoteService.eventType(withdraw, before));
        assertTrue(ManipulationRemoteService.usesPreMutationEvidence(draft));
        assertTrue(ManipulationRemoteService.usesPreMutationEvidence(clear));
        assertTrue(ManipulationRemoteService.usesPreMutationEvidence(withdraw));
        assertTrue(ManipulationRemoteService.usesPostMutationEvidence(submit));
        assertFalse(ManipulationRemoteService.usesPostMutationEvidence(withdraw));
    }

    @Test
    void capableAdminInvariantUsesActiveStatusRoleDefaultsAndExplicitOverrides() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:admin-invariant-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE users(id VARCHAR(64),status VARCHAR(24))");
        jdbc.execute("CREATE TABLE roles(id VARCHAR(64),code VARCHAR(64),name VARCHAR(64))");
        jdbc.execute("CREATE TABLE permissions(id VARCHAR(64),code VARCHAR(96))");
        jdbc.execute("CREATE TABLE user_roles(user_id VARCHAR(64),role_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE role_permissions(role_id VARCHAR(64),permission_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE user_permissions(user_id VARCHAR(64),permission_id VARCHAR(64),granted BOOLEAN)");
        jdbc.update("INSERT INTO roles(id,code,name) VALUES('admin-role','ADMIN','Administrator')");
        jdbc.update("INSERT INTO permissions(id,code) VALUES"
                + "('manage-users','USER_MANAGE'),('manage-permissions','PERMISSION_MANAGE')");
        jdbc.update("INSERT INTO role_permissions(role_id,permission_id) VALUES"
                + "('admin-role','manage-users'),('admin-role','manage-permissions')");
        jdbc.update("INSERT INTO users(id,status) VALUES('admin-a','ACTIVE'),('admin-b','ACTIVE')");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES"
                + "('admin-a','admin-role'),('admin-b','admin-role')");

        assertTrue(ManipulationRemoteService.hasCapableAdmin(jdbc));

        jdbc.update("INSERT INTO user_permissions(user_id,permission_id,granted) "
                + "VALUES('admin-a','manage-users',FALSE)");
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id='admin-b'");
        assertFalse(ManipulationRemoteService.hasCapableAdmin(jdbc));

        jdbc.update("UPDATE user_permissions SET granted=TRUE "
                + "WHERE user_id='admin-a' AND permission_id='manage-users'");
        assertTrue(ManipulationRemoteService.hasCapableAdmin(jdbc));
    }

    @Test
    void signedSemanticProbeCanBeRepeatedWithANewNonceAndReturnsStoredResult() throws Exception {
        var authenticator = mock(edu.chd.practice.rmi.server.security.RequestAuthenticator.class);
        var idempotency = mock(IdempotencyService.class);
        when(idempotency.databaseKey("teacher01", "submit:one")).thenReturn("database-key");
        when(idempotency.requestHash("manipulation.transaction", "teacher01", "fingerprint"))
                .thenReturn("request-hash");
        when(idempotency.find("database-key", "request-hash")).thenReturn(Optional.of(true));
        ManipulationRemoteService service = new ManipulationRemoteService(authenticator,
                mock(edu.chd.practice.rmi.server.security.AuthorizationService.class),
                mock(edu.chd.practice.rmi.server.sql.SafeSqlBuilder.class),
                mock(edu.chd.practice.rmi.server.sql.JdbcPreparedExecutor.class), mock(JdbcTemplate.class),
                mock(org.springframework.transaction.support.TransactionTemplate.class), idempotency,
                mock(AuditService.class), mock(edu.chd.practice.rmi.server.integrity.HashChainLedger.class),
                mock(edu.chd.practice.rmi.server.security.SnapshotCrypto.class),
                mock(edu.chd.practice.rmi.server.security.SecretMaterialProvider.class),
                mock(edu.chd.practice.rmi.server.config.SecurityProperties.class), new ObjectMapper());
        IdempotencyProbe probe = new IdempotencyProbe("submit:one", "fingerprint");
        InvocationContext first = new InvocationContext("request-1", "teacher01", List.of("TEACHER"),
                1L, "nonce-1", "signature-1");
        InvocationContext retry = new InvocationContext("request-2", "teacher01", List.of("TEACHER"),
                2L, "nonce-2", "signature-2");

        assertTrue(service.transactionCompleted(probe, first));
        assertTrue(service.transactionCompleted(probe, retry));

        verify(authenticator).authenticate(eq("manipulation.transaction.idempotency-probe"), eq(probe), eq(first));
        verify(authenticator).authenticate(eq("manipulation.transaction.idempotency-probe"), eq(probe), eq(retry));
    }

    private static MutationCommand makeupCommand(String reason, String status) {
        return new MutationCommand(MutationType.UPDATE, "grades", Map.of("status", status),
                List.of(Filter.of("id", FilterOperator.EQ, "g1")), reason, null);
    }

    private static ObjectNode payload(int regular, Integer raw, Integer effective,
                                      Integer finalScore, String makeupStatus) {
        ObjectNode root = JSON.createObjectNode();
        root.putObject("componentScores").put("USUAL", 70).put("FINAL", 50);
        root.put("regularScore", regular);
        putNullable(root, "makeupRawScore", raw);
        putNullable(root, "makeupEffectiveScore", effective);
        putNullable(root, "finalScore", finalScore == null ? regular : finalScore);
        if (makeupStatus == null) root.putNull("makeupStatus");
        else root.put("makeupStatus", makeupStatus);
        return root;
    }

    private static void putNullable(ObjectNode root, String field, Integer value) {
        if (value == null) root.putNull(field);
        else root.put(field, value);
    }
}
