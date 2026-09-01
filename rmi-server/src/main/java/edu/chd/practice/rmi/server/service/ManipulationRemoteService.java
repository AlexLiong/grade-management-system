package edu.chd.practice.rmi.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.chd.practice.rmi.contract.CanonicalForms;
import edu.chd.practice.rmi.contract.GradeCipherCodec;
import edu.chd.practice.rmi.contract.ManipulationInterface;
import edu.chd.practice.rmi.contract.RemoteServiceException;
import edu.chd.practice.rmi.contract.dto.EncryptedGradePayload;
import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.rmi.contract.dto.FilterOperator;
import edu.chd.practice.rmi.contract.dto.InvocationContext;
import edu.chd.practice.rmi.contract.dto.IdempotencyProbe;
import edu.chd.practice.rmi.contract.dto.MutationCommand;
import edu.chd.practice.rmi.contract.dto.MutationType;
import edu.chd.practice.rmi.contract.dto.TransactionRequest;
import edu.chd.practice.rmi.server.config.SecurityProperties;
import edu.chd.practice.rmi.server.integrity.GradeSnapshotCodec;
import edu.chd.practice.rmi.server.integrity.HashChainLedger;
import edu.chd.practice.rmi.server.integrity.LedgerEvent;
import edu.chd.practice.rmi.server.security.AuthorizationService;
import edu.chd.practice.rmi.server.security.RequestAuthenticator;
import edu.chd.practice.rmi.server.security.SecretMaterialProvider;
import edu.chd.practice.rmi.server.security.SnapshotCrypto;
import edu.chd.practice.rmi.server.sql.JdbcPreparedExecutor;
import edu.chd.practice.rmi.server.sql.PreparedSql;
import edu.chd.practice.rmi.server.sql.SafeSqlBuilder;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ManipulationRemoteService implements ManipulationInterface {
    private static final int MAX_SNAPSHOT_ROWS = 10_000;
    private static final Set<String> ENCRYPTED_GRADE_COLUMNS = Set.of(
            "score_ciphertext", "score_nonce", "score_integrity");
    private static final Set<String> MAKEUP_FIELDS = Set.of(
            "makeupRawScore", "makeupEffectiveScore", "finalScore", "makeupStatus");
    private static final Set<String> IDENTITY_SECURITY_TABLES = Set.of(
            "users", "roles", "permissions", "user_roles", "role_permissions", "user_permissions");
    private static final List<String> CORE_ADMIN_PERMISSIONS = List.of(
            "USER_MANAGE", "PERMISSION_MANAGE");
    private static final List<String> GRADE_COLUMNS = List.of("id", "enrollment_id", "scheme_id",
            "score_ciphertext", "score_nonce", "score_integrity", "key_version", "status", "version",
            "submitted_by", "submitted_at", "updated_at");

    private final RequestAuthenticator authenticator;
    private final AuthorizationService authorization;
    private final SafeSqlBuilder sqlBuilder;
    private final JdbcPreparedExecutor preparedJdbc;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final HashChainLedger ledger;
    private final SnapshotCrypto snapshotCrypto;
    private final SecretMaterialProvider secrets;
    private final SecurityProperties properties;
    private final ObjectMapper objectMapper;

    public ManipulationRemoteService(RequestAuthenticator authenticator, AuthorizationService authorization,
                                     SafeSqlBuilder sqlBuilder, JdbcPreparedExecutor preparedJdbc,
                                     JdbcTemplate jdbc, TransactionTemplate transactions,
                                     IdempotencyService idempotency, AuditService audit,
                                     HashChainLedger ledger, SnapshotCrypto snapshotCrypto,
                                     SecretMaterialProvider secrets, SecurityProperties properties,
                                     ObjectMapper objectMapper) {
        this.authenticator = authenticator;
        this.authorization = authorization;
        this.sqlBuilder = sqlBuilder;
        this.preparedJdbc = preparedJdbc;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.audit = audit;
        this.ledger = ledger;
        this.snapshotCrypto = snapshotCrypto;
        this.secrets = secrets;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean execute(MutationCommand command, InvocationContext context) throws RemoteServiceException {
        boolean fresh = authenticator.authenticate(OP_EXECUTE, command, context);
        return executeAuthenticated(List.of(command), context, context.getRequestId(), OP_EXECUTE,
                command.canonicalForm(), fresh);
    }

    @Override
    public boolean executeTransaction(TransactionRequest request, InvocationContext context)
            throws RemoteServiceException {
        if (request.getCommands().isEmpty() || request.getCommands().size() > properties.getMaxTransactionCommands()) {
            throw new RemoteServiceException("TRANSACTION_SIZE_INVALID", "Transaction command count is outside the allowed range");
        }
        boolean fresh = authenticator.authenticate(OP_TRANSACTION, request, context);
        if (request.getSemanticFingerprint() != null) {
            requireIdempotencyFields(request.getIdempotencyKey(), request.getSemanticFingerprint());
        }
        String idempotencyPayload = request.getSemanticFingerprint() == null
                ? request.canonicalForm() : request.getSemanticFingerprint();
        return executeAuthenticated(request.getCommands(), context, request.getIdempotencyKey(), OP_TRANSACTION,
                idempotencyPayload, fresh);
    }

    @Override
    public boolean transactionCompleted(IdempotencyProbe probe, InvocationContext context)
            throws RemoteServiceException {
        requireIdempotencyFields(probe.getIdempotencyKey(), probe.getSemanticFingerprint());
        authenticator.authenticate(OP_IDEMPOTENCY_PROBE, probe, context);
        String databaseKey = idempotency.databaseKey(context.getPrincipal(), probe.getIdempotencyKey());
        String requestHash = idempotency.requestHash(OP_TRANSACTION, context.getPrincipal(),
                probe.getSemanticFingerprint());
        return idempotency.find(databaseKey, requestHash).orElse(false);
    }

    private boolean executeAuthenticated(List<MutationCommand> commands, InvocationContext context,
                                         String key, String operation, String canonicalPayload,
                                         boolean fresh) throws RemoteServiceException {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new RemoteServiceException("IDEMPOTENCY_KEY_INVALID", "Idempotency key must contain 1 to 128 characters");
        }
        String databaseKey = idempotency.databaseKey(context.getPrincipal(), key);
        String requestHash = idempotency.requestHash(operation, context.getPrincipal(), canonicalPayload);
        Optional<Boolean> cached = idempotency.find(databaseKey, requestHash);
        if (cached.isPresent()) return cached.get();
        if (!fresh) throw new RemoteServiceException("AUTH_REPLAY", "Invocation nonce has already been used");

        try {
            Boolean result = transactions.execute(status -> {
                List<LedgerEvent> events = new ArrayList<>();
                idempotency.reserve(databaseKey, requestHash);
                boolean protectAdminInvariant = commands.stream()
                        .anyMatch(command -> IDENTITY_SECURITY_TABLES.contains(command.getTable()));
                if (protectAdminInvariant) {
                    lockAdminInvariant();
                }
                for (MutationCommand command : commands) {
                    perform(command, context, events);
                }
                if (protectAdminInvariant && !hasCapableAdmin(jdbc)) {
                    fail("LAST_CAPABLE_ADMIN_REQUIRED", "users",
                            "At least one active administrator must retain user and permission management");
                }
                idempotency.complete(databaseKey, requestHash);
                ledger.appendBatch(events);
                return Boolean.TRUE;
            });
            return Boolean.TRUE.equals(result);
        } catch (MutationFailure exception) {
            recordFailure(context, operation, exception.table, exception.code);
            throw new RemoteServiceException(exception.code, exception.getMessage());
        } catch (DataAccessException exception) {
            Optional<Boolean> raced = idempotency.find(databaseKey, requestHash);
            if (raced.isPresent()) {
                return raced.get();
            }
            recordFailure(context, operation, "transaction", "TRANSACTION_ROLLED_BACK");
            throw new RemoteServiceException("TRANSACTION_ROLLED_BACK", "A database constraint rejected the transaction");
        } catch (RuntimeException exception) {
            recordFailure(context, operation, "transaction", "TRANSACTION_ROLLED_BACK");
            throw new RemoteServiceException("TRANSACTION_ROLLED_BACK", "The complete transaction was rolled back");
        }
    }

    private void requireIdempotencyFields(String key, String fingerprint) throws RemoteServiceException {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new RemoteServiceException("IDEMPOTENCY_KEY_INVALID",
                    "Idempotency key must contain 1 to 128 characters");
        }
        if (fingerprint == null || fingerprint.isBlank() || fingerprint.length() > 128) {
            throw new RemoteServiceException("IDEMPOTENCY_FINGERPRINT_INVALID",
                    "Semantic fingerprint must contain 1 to 128 characters");
        }
    }

    private void lockAdminInvariant() {
        int locked = jdbc.update("UPDATE roles SET name=name WHERE code='ADMIN'");
        if (locked != 1) {
            fail("LAST_CAPABLE_ADMIN_REQUIRED", "roles", "The administrator role is missing or ambiguous");
        }
    }

    static boolean hasCapableAdmin(JdbcTemplate jdbc) {
        List<String> administratorIds = jdbc.query("""
                SELECT DISTINCT u.id FROM users u
                JOIN user_roles ur ON ur.user_id=u.id
                JOIN roles r ON r.id=ur.role_id
                WHERE u.status='ACTIVE' AND r.code='ADMIN'
                """, (resultSet, row) -> resultSet.getString(1));
        return administratorIds.stream().anyMatch(userId -> CORE_ADMIN_PERMISSIONS.stream()
                .allMatch(permission -> hasEffectivePermission(jdbc, userId, permission)));
    }

    private static boolean hasEffectivePermission(JdbcTemplate jdbc, String userId, String permission) {
        List<Boolean> overrides = jdbc.query("""
                SELECT up.granted FROM user_permissions up
                JOIN permissions p ON p.id=up.permission_id
                WHERE up.user_id=? AND p.code=?
                """, (resultSet, row) -> resultSet.getBoolean(1), userId, permission);
        if (!overrides.isEmpty()) {
            return Boolean.TRUE.equals(overrides.get(0));
        }
        Long defaults = jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_roles ur
                JOIN role_permissions rp ON rp.role_id=ur.role_id
                JOIN permissions p ON p.id=rp.permission_id
                WHERE ur.user_id=? AND p.code=?
                """, Long.class, userId, permission);
        return defaults != null && defaults > 0;
    }

    private void perform(MutationCommand command, InvocationContext context, List<LedgerEvent> events) {
        try {
            Set<String> roles = authorization.authorizeWrite(command, context);
            List<Map<String, String>> before = "grades".equals(command.getTable())
                    && command.getType() != MutationType.INSERT ? capture(command.getFilters()) : List.of();
            if (before.size() > MAX_SNAPSHOT_ROWS) fail("SNAPSHOT_LIMIT", command.getTable(), "Too many grades in one mutation");
            validateGradeMutation(command, before);
            ReversionKind reversion = reversionKind(command, before);
            if (reversion != ReversionKind.NONE) validateReversion(command, context, roles, reversion);
            if (reversion == ReversionKind.LARGE) consumeApproval(command);

            PreparedSql prepared = sqlBuilder.mutation(command);
            int affected = preparedJdbc.update(prepared);
            if (affected < 1) fail("NO_ROWS_AFFECTED", command.getTable(), "Mutation did not affect a record");
            if (!before.isEmpty() && affected != before.size()) {
                fail("SNAPSHOT_MISMATCH", command.getTable(), "Mutation scope changed after snapshot capture");
            }

            List<Map<String, String>> evidence = evidenceRows(command, before);
            if (!evidence.isEmpty()) {
                String eventType = eventType(command, before);
                String scope = reversion == ReversionKind.LARGE ? "LARGE" : "SMALL";
                for (Map<String, String> row : evidence) {
                    storeHistory(row, eventType, scope, command, context);
                    String encrypted = snapshotCrypto.encrypt(GradeSnapshotCodec.encode(row));
                    events.add(new LedgerEvent(eventType, "GRADE", row.get("id"), encrypted,
                            context.getPrincipal()));
                }
            }
            audit.success(context, command, affected);
        } catch (RemoteServiceException exception) {
            throw new MutationFailure(exception.getCode(), command.getTable(), exception.getMessage(), exception);
        } catch (IllegalArgumentException exception) {
            throw new MutationFailure("MUTATION_INVALID", command.getTable(), exception.getMessage(), exception);
        }
    }

    private List<Map<String, String>> evidenceRows(MutationCommand command, List<Map<String, String>> before) {
        if (!"grades".equals(command.getTable())) return List.of();
        if (command.getType() == MutationType.INSERT
                && "SUBMITTED".equals(command.getValues().get("status"))) {
            String id = command.getValues().get("id");
            return capture(List.of(Filter.of("id", FilterOperator.EQ, id)));
        }
        if (usesPostMutationEvidence(command)) return captureByRows(before);
        if (usesPreMutationEvidence(command)) return before;
        if ("SUBMITTED".equals(command.getValues().get("status"))) return captureByRows(before);
        if (reversionKind(command, before) != ReversionKind.NONE || changesEncryptedScore(command, before)) return before;
        return List.of();
    }

    static boolean usesPreMutationEvidence(MutationCommand command) {
        return command.getReason() != null
                && Set.of("MAKEUP_DRAFT", "MAKEUP_CLEAR", "MAKEUP_WITHDRAW").contains(command.getReason());
    }

    static boolean usesPostMutationEvidence(MutationCommand command) {
        return "MAKEUP_SUBMIT".equals(command.getReason());
    }

    private List<Map<String, String>> captureByRows(List<Map<String, String>> rows) {
        String[] ids = rows.stream().map(row -> row.get("id")).toArray(String[]::new);
        return ids.length == 0 ? List.of() : capture(List.of(Filter.of("id", FilterOperator.IN, ids)));
    }

    private List<Map<String, String>> capture(List<Filter> filters) {
        return preparedJdbc.queryMaps(sqlBuilder.snapshot("grades", GRADE_COLUMNS, filters));
    }

    private void validateGradeMutation(MutationCommand command, List<Map<String, String>> before) {
        if (!"grades".equals(command.getTable())) return;
        if (command.getType() == MutationType.INSERT) {
            String gradeId = command.getValues().get("id");
            if (gradeId == null) fail("GRADE_ID_REQUIRED", "grades", "Grade INSERT requires an explicit UUID id");
            verifyEncryptedValues(gradeId, command.getValues());
            return;
        }
        validateImmutableGradeColumns(command, before);
        long encryptedValueCount = command.getValues().keySet().stream()
                .filter(ENCRYPTED_GRADE_COLUMNS::contains).count();
        boolean encryptedChanged = encryptedValueCount > 0;
        if (encryptedChanged && encryptedValueCount != ENCRYPTED_GRADE_COLUMNS.size()) {
            fail("GRADE_PAYLOAD_INCOMPLETE", "grades", "Encrypted grade columns must be replaced together");
        }
        if (encryptedChanged) {
            if (before.size() != 1) fail("GRADE_SCOPE_INVALID", "grades", "Encrypted score updates must target one grade");
            Map<String, String> merged = new HashMap<>(before.get(0));
            merged.putAll(command.getValues());
            verifyEncryptedValues(before.get(0).get("id"), merged);
            if ("SUBMITTED".equals(before.get(0).get("status"))
                    && !isMakeupReason(command.getReason())
                    && !isAdminSmallReversionReason(command.getReason())
                    && !isRegularWithdrawalReason(command.getReason())) {
                fail("SUBMITTED_GRADE_IMMUTABLE", "grades",
                        "Submitted grade ciphertext may only change through a dedicated transition");
            }
        }
        if (isMakeupReason(command.getReason())) {
            if (before.size() != 1 || !encryptedChanged) {
                fail("MAKEUP_SCOPE_INVALID", "grades", "Makeup transition must replace one encrypted grade payload");
            }
            Map<String, String> after = new HashMap<>(before.get(0));
            after.putAll(command.getValues());
            validateMakeupPayload(command.getReason(), before.get(0), after);
        }
        if (isAdminSmallReversionReason(command.getReason())
                || isRegularWithdrawalReason(command.getReason())) {
            if (before.size() != 1) {
                fail("GRADE_SCOPE_INVALID", "grades", "Small reversion must target exactly one grade");
            }
            Map<String, String> after = new HashMap<>(before.get(0));
            after.putAll(command.getValues());
            validateRegularWithdrawalPayload(before.get(0), after,
                    isAdminSmallReversionReason(command.getReason()));
        }
    }

    private void validateImmutableGradeColumns(MutationCommand command, List<Map<String, String>> before) {
        for (String column : Set.of("id", "enrollment_id", "scheme_id", "key_version")) {
            String replacement = command.getValues().get(column);
            if (replacement != null && before.stream().anyMatch(row -> !Objects.equals(row.get(column), replacement))) {
                fail("GRADE_RELATION_IMMUTABLE", "grades",
                        "Grade identity, enrollment, scheme, and key version cannot be replaced");
            }
        }
    }

    private void verifyEncryptedValues(String gradeId, Map<String, String> values) {
        EncryptedGradePayload payload = new EncryptedGradePayload(values.get("score_ciphertext"),
                values.get("score_nonce"), values.get("score_integrity"));
        if (!GradeCipherCodec.verify(secrets.gradeKey(), gradeId, payload)) {
            fail("GRADE_INTEGRITY_INVALID", "grades", "Encrypted grade payload failed integrity verification");
        }
    }

    private void validateMakeupPayload(String reason, Map<String, String> beforeValues,
                                       Map<String, String> afterValues) {
        JsonNode before = decryptGradePayload(beforeValues);
        JsonNode after = decryptGradePayload(afterValues);
        try {
            validateMakeupTransition(reason, before, after);
        } catch (IllegalArgumentException exception) {
            fail("MAKEUP_TRANSITION_INVALID", "grades", exception.getMessage());
        }
    }

    private void validateRegularWithdrawalPayload(Map<String, String> beforeValues,
                                                  Map<String, String> afterValues,
                                                  boolean adminReversion) {
        JsonNode before = decryptGradePayload(beforeValues);
        JsonNode after = decryptGradePayload(afterValues);
        try {
            validateCommonPayloadInvariants(before, after, false);
            String beforeMakeupStatus = nullableText(before.get("makeupStatus"));
            if (!adminReversion && beforeMakeupStatus != null && !"DRAFT".equals(beforeMakeupStatus)) {
                throw new IllegalArgumentException("Submitted makeup must be withdrawn before the regular grade");
            }
            if (!isNullish(after.get("makeupRawScore"))
                    || !isNullish(after.get("makeupEffectiveScore"))
                    || !isNullish(after.get("makeupStatus"))) {
                throw new IllegalArgumentException("Small reversion must remove the complete makeup attempt");
            }
            requireSameDecimal(after.get("finalScore"), after.get("regularScore"),
                    "Small reversion must restore finalScore to regularScore");
        } catch (IllegalArgumentException exception) {
            fail("SMALL_REVERSION_PAYLOAD_INVALID", "grades", exception.getMessage());
        }
    }

    private JsonNode decryptGradePayload(Map<String, String> values) {
        String gradeId = values.get("id");
        EncryptedGradePayload payload = new EncryptedGradePayload(values.get("score_ciphertext"),
                values.get("score_nonce"), values.get("score_integrity"));
        try {
            JsonNode root = objectMapper.readTree(GradeCipherCodec.decrypt(secrets.gradeKey(), gradeId, payload));
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Grade payload must be a JSON object");
            }
            return root;
        } catch (JsonProcessingException | IllegalArgumentException | SecurityException exception) {
            throw new MutationFailure("GRADE_PAYLOAD_INVALID", "grades",
                    "Encrypted grade plaintext is malformed or unauthenticated", exception);
        }
    }

    static void validateMakeupTransition(String reason, JsonNode before, JsonNode after) {
        validateCommonPayloadInvariants(before, after, true);
        String beforeStatus = nullableText(before.get("makeupStatus"));
        String afterStatus = nullableText(after.get("makeupStatus"));
        switch (reason) {
            case "MAKEUP_DRAFT" -> {
                if (beforeStatus != null && !"DRAFT".equals(beforeStatus)) {
                    throw new IllegalArgumentException("Only an empty or draft makeup attempt may be edited");
                }
                if (!"DRAFT".equals(afterStatus)) {
                    throw new IllegalArgumentException("Makeup draft must retain DRAFT status");
                }
                validateMakeupScores(after);
            }
            case "MAKEUP_CLEAR" -> {
                if (!"DRAFT".equals(beforeStatus)) {
                    throw new IllegalArgumentException("Only a makeup draft may be cleared");
                }
                validateMakeupScores(before);
                if (!"DRAFT".equals(afterStatus) || !isNullish(after.get("makeupRawScore"))
                        || !isNullish(after.get("makeupEffectiveScore"))) {
                    throw new IllegalArgumentException(
                            "Clearing a makeup draft must remove its scores and retain DRAFT status");
                }
                requireSameDecimal(after.get("finalScore"), after.get("regularScore"),
                        "Clearing a makeup draft must restore finalScore to regularScore");
            }
            case "MAKEUP_SUBMIT" -> {
                if (!"DRAFT".equals(beforeStatus) || !"SUBMITTED".equals(afterStatus)) {
                    throw new IllegalArgumentException("Makeup submission requires DRAFT to SUBMITTED");
                }
                requireSameNode(before.get("makeupRawScore"), after.get("makeupRawScore"),
                        "Makeup submission cannot replace makeupRawScore");
                requireSameNode(before.get("makeupEffectiveScore"), after.get("makeupEffectiveScore"),
                        "Makeup submission cannot replace makeupEffectiveScore");
                requireSameNode(before.get("finalScore"), after.get("finalScore"),
                        "Makeup submission cannot replace finalScore");
                validateMakeupScores(after);
            }
            case "MAKEUP_WITHDRAW" -> {
                if (!"SUBMITTED".equals(beforeStatus) || !"DRAFT".equals(afterStatus)) {
                    throw new IllegalArgumentException("Makeup withdrawal requires SUBMITTED to DRAFT");
                }
                requireSameNode(before.get("makeupRawScore"), after.get("makeupRawScore"),
                        "Makeup withdrawal cannot replace makeupRawScore");
                requireSameNode(before.get("makeupEffectiveScore"), after.get("makeupEffectiveScore"),
                        "Makeup withdrawal cannot replace makeupEffectiveScore");
                requireSameNode(before.get("finalScore"), after.get("finalScore"),
                        "Makeup withdrawal cannot replace finalScore");
                validateMakeupScores(after);
            }
            default -> throw new IllegalArgumentException("Unsupported makeup transition");
        }
    }

    private static void validateCommonPayloadInvariants(JsonNode before, JsonNode after,
                                                        boolean requireFailedRegularScore) {
        if (before == null || after == null || !before.isObject() || !after.isObject()) {
            throw new IllegalArgumentException("Grade payload must be a JSON object");
        }
        JsonNode beforeComponents = before.get("componentScores");
        JsonNode afterComponents = after.get("componentScores");
        if (beforeComponents == null || !beforeComponents.isObject()
                || afterComponents == null || !afterComponents.isObject()
                || !beforeComponents.equals(afterComponents)) {
            throw new IllegalArgumentException("Regular component scores are immutable during this transition");
        }
        BigDecimal beforeRegular = requireDecimal(before.get("regularScore"), "regularScore is required");
        BigDecimal afterRegular = requireDecimal(after.get("regularScore"), "regularScore is required");
        if (beforeRegular.compareTo(afterRegular) != 0) {
            throw new IllegalArgumentException("regularScore is immutable during this transition");
        }
        if (requireFailedRegularScore && beforeRegular.compareTo(BigDecimal.valueOf(60)) >= 0) {
            throw new IllegalArgumentException("Makeup is only allowed when regularScore is below 60");
        }

        Set<String> fieldNames = new HashSet<>();
        before.fieldNames().forEachRemaining(fieldNames::add);
        after.fieldNames().forEachRemaining(fieldNames::add);
        for (String field : fieldNames) {
            if (!MAKEUP_FIELDS.contains(field)) {
                requireSameNode(before.get(field), after.get(field),
                        "Only makeup fields and finalScore may change");
            }
        }
    }

    private static void validateMakeupScores(JsonNode payload) {
        BigDecimal raw = requireDecimal(payload.get("makeupRawScore"), "makeupRawScore is required");
        if (raw.signum() < 0 || raw.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("makeupRawScore must be between 0 and 100");
        }
        BigDecimal effective = requireDecimal(payload.get("makeupEffectiveScore"),
                "makeupEffectiveScore is required");
        BigDecimal expected = raw.min(BigDecimal.valueOf(60));
        if (effective.compareTo(expected) != 0) {
            throw new IllegalArgumentException("makeupEffectiveScore must equal min(makeupRawScore, 60)");
        }
        requireSameDecimal(payload.get("finalScore"), payload.get("makeupEffectiveScore"),
                "finalScore must equal makeupEffectiveScore while a makeup attempt exists");
    }

    private static void requireSameNode(JsonNode first, JsonNode second, String message) {
        if (isNullish(first) && isNullish(second)) return;
        if (!Objects.equals(first, second)) throw new IllegalArgumentException(message);
    }

    private static void requireSameDecimal(JsonNode first, JsonNode second, String message) {
        if (requireDecimal(first, message).compareTo(requireDecimal(second, message)) != 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private static BigDecimal requireDecimal(JsonNode value, String message) {
        if (value == null || !value.isNumber()) throw new IllegalArgumentException(message);
        return value.decimalValue();
    }

    private static String nullableText(JsonNode value) {
        if (isNullish(value)) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("makeupStatus must be text or null");
        return value.textValue();
    }

    private static boolean isNullish(JsonNode value) {
        return value == null || value.isNull();
    }

    private static boolean isMakeupReason(String reason) {
        return reason != null && Set.of("MAKEUP_DRAFT", "MAKEUP_CLEAR", "MAKEUP_SUBMIT", "MAKEUP_WITHDRAW")
                .contains(reason);
    }

    private static boolean isAdminSmallReversionReason(String reason) {
        return reason != null && reason.startsWith("ADMIN_SMALL_REVERSION:");
    }

    private static boolean isRegularWithdrawalReason(String reason) {
        return reason != null && reason.startsWith("REGULAR_WITHDRAW:");
    }

    private void validateReversion(MutationCommand command, InvocationContext context, Set<String> roles,
                                   ReversionKind kind)
            throws RemoteServiceException {
        if (command.getReason() == null || command.getReason().isBlank() || command.getReason().length() > 1024) {
            throw new RemoteServiceException("REVERSION_REASON_REQUIRED", "Grade reversion requires a reason");
        }
        if (kind == ReversionKind.SMALL) {
            authorization.requirePermission(context, roles.contains("ADMIN")
                    ? "GRADE_REVERT_SMALL" : "GRADE_WITHDRAW");
            return;
        }
        authorization.requirePermission(context, "GRADE_REVERT_APPROVE");
        if (command.getApprovalId() == null || command.getApprovalId().isBlank()) {
            throw new RemoteServiceException("APPROVAL_REQUIRED", "Large grade reversion requires approval");
        }
        List<Approval> approvals = jdbc.query("""
                SELECT rr.id,rr.requested_by,rr.target_filter,a.approver
                FROM reversion_requests rr JOIN high_risk_approvals a ON a.reversion_request_id=rr.id
                WHERE rr.id=? AND rr.scope='LARGE' AND rr.status='APPROVED' AND a.decision='APPROVED'
                """, (rs, row) -> new Approval(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4)), command.getApprovalId());
        String expectedTarget = CanonicalForms.collection(command.getFilters());
        boolean valid = approvals.stream().anyMatch(approval -> isValidLargeApproval(
                approval.requestedBy(), approval.approver(), approval.targetFilter(), expectedTarget));
        if (!valid) throw new RemoteServiceException("APPROVAL_INVALID", "Large grade reversion approval is invalid or mismatched");
    }

    private void consumeApproval(MutationCommand command) {
        int consumed = jdbc.update("UPDATE reversion_requests SET status='EXECUTED',executed_at=CURRENT_TIMESTAMP "
                + "WHERE id=? AND scope='LARGE' AND status='APPROVED'", command.getApprovalId());
        if (consumed != 1) fail("APPROVAL_CONFLICT", command.getTable(),
                "Large grade reversion approval was already consumed");
    }

    static boolean isValidLargeApproval(String requestedBy, String approver,
                                        String targetFilter, String expectedTarget) {
        return requestedBy != null && approver != null && !Objects.equals(requestedBy, approver)
                && Objects.equals(targetFilter, expectedTarget);
    }

    private void storeHistory(Map<String, String> row, String action, String scope,
                              MutationCommand command, InvocationContext context) {
        jdbc.update("""
                INSERT INTO grade_history(id,grade_id,action,original_ciphertext,original_nonce,
                  original_integrity,reason,scope,batch_id,actor_id,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), row.get("id"), action, row.get("score_ciphertext"),
                row.get("score_nonce"), row.get("score_integrity"), command.getReason(), scope,
                context.getRequestId(), context.getPrincipal());
    }

    private static boolean changesEncryptedScore(MutationCommand command, List<Map<String, String>> before) {
        return !before.isEmpty() && command.getValues().keySet().stream().anyMatch(
                Set.of("score_ciphertext", "score_nonce", "score_integrity")::contains);
    }

    private static ReversionKind reversionKind(MutationCommand command, List<Map<String, String>> before) {
        if (!"grades".equals(command.getTable())) return ReversionKind.NONE;
        if (isLargeGradeDeletion(command)) return ReversionKind.LARGE;
        return command.getType() == MutationType.UPDATE
                && "DRAFT".equals(command.getValues().get("status"))
                && before.stream().anyMatch(row -> "SUBMITTED".equals(row.get("status")))
                ? ReversionKind.SMALL : ReversionKind.NONE;
    }

    static boolean isLargeGradeDeletion(MutationCommand command) {
        return "grades".equals(command.getTable()) && command.getType() == MutationType.DELETE;
    }

    static String eventType(MutationCommand command, List<Map<String, String>> before) {
        if (command.getType() == MutationType.UPDATE) {
            if ("MAKEUP_DRAFT".equals(command.getReason())) return "GRADE_MAKEUP_DRAFT";
            if ("MAKEUP_CLEAR".equals(command.getReason())) return "GRADE_MAKEUP_CLEAR";
            if ("MAKEUP_SUBMIT".equals(command.getReason())) return "GRADE_MAKEUP_SUBMIT";
            if ("MAKEUP_WITHDRAW".equals(command.getReason())) return "GRADE_MAKEUP_WITHDRAW";
            if (isRegularWithdrawalReason(command.getReason())) return "GRADE_REGULAR_WITHDRAW";
            if (isAdminSmallReversionReason(command.getReason())) return "GRADE_REVERT_SMALL";
        }
        ReversionKind reversion = reversionKind(command, before);
        if (reversion == ReversionKind.LARGE) return "GRADE_REVERT_LARGE";
        if (reversion == ReversionKind.SMALL) return "GRADE_REVERT_SMALL";
        if ((command.getType() == MutationType.INSERT
                && "SUBMITTED".equals(command.getValues().get("status")))
                || "SUBMITTED".equals(command.getValues().get("status"))) {
            return "GRADE_SUBMIT";
        }
        return "GRADE_CORRECTION";
    }

    private void recordFailure(InvocationContext context, String operation, String table, String code) {
        try { audit.failure(context, operation, table, code); }
        catch (RuntimeException ignored) { /* Preserve the original remote failure. */ }
    }

    private static void fail(String code, String table, String message) {
        throw new MutationFailure(code, table, message, null);
    }

    private record Approval(String id, String requestedBy, String targetFilter, String approver) { }

    private enum ReversionKind { NONE, SMALL, LARGE }

    private static final class MutationFailure extends RuntimeException {
        private final String code;
        private final String table;

        private MutationFailure(String code, String table, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.table = table;
        }
    }
}
