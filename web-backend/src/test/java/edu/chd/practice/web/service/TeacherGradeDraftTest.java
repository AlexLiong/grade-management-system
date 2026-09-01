package edu.chd.practice.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.chd.practice.rmi.contract.dto.SelectRequest;
import edu.chd.practice.rmi.contract.dto.TransactionRequest;
import edu.chd.practice.web.crypto.GradeCryptoService;
import edu.chd.practice.web.dto.GradeDtos;
import edu.chd.practice.web.error.ApiException;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import edu.chd.practice.web.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

class TeacherGradeDraftTest {
    private static final List<TeacherGradeService.WeightRule> RULES = List.of(
            new TeacherGradeService.WeightRule("DAILY", new BigDecimal("30"), new BigDecimal("100")),
            new TeacherGradeService.WeightRule("LAB", new BigDecimal("20"), new BigDecimal("100")),
            new TeacherGradeService.WeightRule("FINAL", new BigDecimal("50"), new BigDecimal("100"))
    );

    @Test
    void mergesPartialRegularDraftWithExistingComponents() {
        TeacherGradeService.GradePayload existing = new TeacherGradeService.GradePayload(
                Map.of("DAILY", new BigDecimal("80"), "LAB", new BigDecimal("90")),
                null, null, null, null, null);
        GradeDtos.GradeEntryInput input = new GradeDtos.GradeEntryInput("enrollment-1",
                Map.of("FINAL", new BigDecimal("70")), null, GradeDtos.ExamType.REGULAR, 1);

        TeacherGradeService.GradePayload result = TeacherGradeService.calculate(input, RULES, existing);

        assertThat(result.componentScores()).containsEntry("DAILY", new BigDecimal("80.00"))
                .containsEntry("LAB", new BigDecimal("90.00"))
                .containsEntry("FINAL", new BigDecimal("70.00"));
        assertThat(result.regularScore()).isEqualByComparingTo("77.00");
    }

    @Test
    void withdrawingMakeupPreservesEnteredScoresAsDraft() {
        TeacherGradeService.GradePayload submitted = new TeacherGradeService.GradePayload(
                Map.of("DAILY", new BigDecimal("50")), new BigDecimal("58"),
                new BigDecimal("76"), new BigDecimal("60"), new BigDecimal("60"), "SUBMITTED");

        TeacherGradeService.GradePayload result = TeacherGradeService.withdrawMakeup(submitted);

        assertThat(result.makeupRawScore()).isEqualByComparingTo("76");
        assertThat(result.makeupEffectiveScore()).isEqualByComparingTo("60");
        assertThat(result.finalScore()).isEqualByComparingTo("60");
        assertThat(result.makeupStatus()).isEqualTo("DRAFT");
    }

    @Test
    void withdrawingRegularAfterMakeupDraftClearsOnlyMakeupAttempt() {
        TeacherGradeService.GradePayload makeupDraft = new TeacherGradeService.GradePayload(
                Map.of("DAILY", new BigDecimal("50")), new BigDecimal("58"),
                new BigDecimal("76"), new BigDecimal("60"), new BigDecimal("60"), "DRAFT");

        TeacherGradeService.GradePayload result = TeacherGradeService.clearMakeup(makeupDraft);

        assertThat(result.componentScores()).isEqualTo(makeupDraft.componentScores());
        assertThat(result.regularScore()).isEqualByComparingTo("58");
        assertThat(result.makeupRawScore()).isNull();
        assertThat(result.makeupEffectiveScore()).isNull();
        assertThat(result.finalScore()).isEqualByComparingTo("58");
        assertThat(result.makeupStatus()).isNull();
    }

    @Test
    void clearingSavedMakeupDraftRetainsEditableDraftState() {
        TeacherGradeService.GradePayload makeupDraft = new TeacherGradeService.GradePayload(
                Map.of("DAILY", new BigDecimal("50")), new BigDecimal("58"),
                new BigDecimal("76"), new BigDecimal("60"), new BigDecimal("60"), "DRAFT");
        GradeDtos.GradeEntryInput input = new GradeDtos.GradeEntryInput("enrollment-1", Map.of(),
                null, GradeDtos.ExamType.RETAKE, 7);

        TeacherGradeService.GradePayload result = TeacherGradeService.calculate(input, RULES, makeupDraft);

        assertThat(result.componentScores()).isEqualTo(makeupDraft.componentScores());
        assertThat(result.regularScore()).isEqualByComparingTo("58");
        assertThat(result.makeupRawScore()).isNull();
        assertThat(result.makeupEffectiveScore()).isNull();
        assertThat(result.finalScore()).isEqualByComparingTo("58");
        assertThat(result.makeupStatus()).isEqualTo("DRAFT");
    }

    @Test
    void nullRetakeWithoutASavedScoreIsRejected() {
        TeacherGradeService.GradePayload noMakeupAttempt = new TeacherGradeService.GradePayload(
                Map.of("DAILY", new BigDecimal("50")), new BigDecimal("58"),
                null, null, new BigDecimal("58"), null);
        GradeDtos.GradeEntryInput input = new GradeDtos.GradeEntryInput("enrollment-1", Map.of(),
                null, GradeDtos.ExamType.RETAKE, 7);

        assertThatThrownBy(() -> TeacherGradeService.calculate(input, RULES, noMakeupAttempt))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("MAKEUP_DRAFT_CLEAR_INVALID"));
    }

    @Test
    void clearMakeupDraftUsesDedicatedOptimisticTransactionAndReturnsClearedDraft() throws Exception {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        ResourceAccessService access = mock(ResourceAccessService.class);
        GradeCryptoService crypto = mock(GradeCryptoService.class);
        AuditService audit = mock(AuditService.class);
        when(access.requirePermission(anyString())).thenReturn(teacher("GRADE_DRAFT_WRITE", "GRADE_READ"));
        when(gateway.select(any(SelectRequest.class))).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            return switch (request.getTable()) {
                case "grading_schemes" -> List.of(Map.of(
                        "id", "scheme-1", "offering_id", "offering-1", "version", "1"));
                case "grading_weights" -> List.of(
                        Map.of("item_code", "DAILY", "weight", "30", "max_score", "100"),
                        Map.of("item_code", "LAB", "weight", "20", "max_score", "100"),
                        Map.of("item_code", "FINAL", "weight", "50", "max_score", "100"));
                case "enrollments" -> List.of(Map.of(
                        "id", "enrollment-1", "student_id", "student-1"));
                case "students" -> List.of(Map.of(
                        "id", "student-1", "student_no", "001", "name", "甲"));
                case "grades" -> List.of(Map.of(
                        "id", "grade-1", "enrollment_id", "enrollment-1", "scheme_id", "scheme-1",
                        "score_ciphertext", "old-cipher", "score_nonce", "old-nonce",
                        "score_integrity", "old-integrity", "status", "SUBMITTED", "version", "7"));
                default -> throw new AssertionError("Unexpected table " + request.getTable());
            };
        });
        when(crypto.decrypt("grade-1", "old-cipher", "old-nonce", "old-integrity"))
                .thenReturn("{\"componentScores\":{\"DAILY\":50,\"LAB\":55,\"FINAL\":60},"
                        + "\"regularScore\":57.5,\"makeupRawScore\":76,\"makeupEffectiveScore\":60,"
                        + "\"finalScore\":60,\"makeupStatus\":\"DRAFT\"}");
        when(crypto.encrypt(eq("grade-1"), anyString())).thenReturn(
                new GradeCryptoService.ProtectedGrade("new-cipher", "new-nonce", "new-integrity"));
        TeacherGradeService service = new TeacherGradeService(gateway, access, crypto,
                new ObjectMapper(), audit);
        GradeDtos.BatchDraftRequest request = new GradeDtos.BatchDraftRequest("scheme-1", List.of(
                new GradeDtos.GradeEntryInput("enrollment-1", Map.of(), null,
                        GradeDtos.ExamType.RETAKE, 7)), "draft:clear-1");

        List<GradeDtos.GradeView> result = service.saveDrafts("offering-1", request);

        assertThat(result).singleElement().satisfies(view -> {
            assertThat(view.makeupRawScore()).isNull();
            assertThat(view.makeupEffectiveScore()).isNull();
            assertThat(view.makeupStatus()).isEqualTo("DRAFT");
            assertThat(view.finalScore()).isEqualByComparingTo("57.5");
            assertThat(view.status()).isEqualTo("SUBMITTED");
            assertThat(view.version()).isEqualTo(8);
        });
        ArgumentCaptor<String> plaintext = ArgumentCaptor.forClass(String.class);
        verify(crypto).encrypt(eq("grade-1"), plaintext.capture());
        var payload = new ObjectMapper().readTree(plaintext.getValue());
        assertThat(payload.get("makeupRawScore").isNull()).isTrue();
        assertThat(payload.get("makeupEffectiveScore").isNull()).isTrue();
        assertThat(payload.get("makeupStatus").asText()).isEqualTo("DRAFT");
        assertThat(payload.get("finalScore").decimalValue()).isEqualByComparingTo("57.5");
        ArgumentCaptor<TransactionRequest> transaction = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(gateway).transaction(transaction.capture());
        assertThat(transaction.getValue().getIdempotencyKey()).isEqualTo("draft:clear-1");
        assertThat(transaction.getValue().getCommands()).singleElement().satisfies(command -> {
            assertThat(command.getReason()).isEqualTo("MAKEUP_CLEAR");
            assertThat(command.getValues()).containsEntry("status", "SUBMITTED")
                    .containsEntry("version", "8")
                    .containsEntry("score_ciphertext", "new-cipher")
                    .doesNotContainKeys("enrollment_id", "scheme_id", "key_version");
            assertThat(command.getFilters()).anySatisfy(filter -> {
                assertThat(filter.getColumn()).isEqualTo("version");
                assertThat(filter.getValues()).containsExactly("7");
            });
        });
        verify(audit).record("GRADE_BATCH_DRAFT", "grades", "offering-1", true,
                "count=1; makeupCleared=1");
    }

    @Test
    void neverSavedOrAlreadyClearedNullRetakeDoesNotCreateMutationNoise() {
        assertNullRetakeRejectedWithoutTransaction(
                "{\"componentScores\":{},\"regularScore\":58,\"makeupRawScore\":null,"
                        + "\"makeupEffectiveScore\":null,\"finalScore\":58,\"makeupStatus\":null}",
                "MAKEUP_DRAFT_CLEAR_INVALID");
        assertNullRetakeRejectedWithoutTransaction(
                "{\"componentScores\":{},\"regularScore\":58,\"makeupRawScore\":null,"
                        + "\"makeupEffectiveScore\":null,\"finalScore\":58,\"makeupStatus\":\"DRAFT\"}",
                "MAKEUP_DRAFT_CLEAR_INVALID");
    }

    @Test
    void submittedMakeupCannotBeClearedBeforeWithdrawal() {
        assertNullRetakeRejectedWithoutTransaction(
                "{\"componentScores\":{},\"regularScore\":58,\"makeupRawScore\":76,"
                        + "\"makeupEffectiveScore\":60,\"finalScore\":60,\"makeupStatus\":\"SUBMITTED\"}",
                "MAKEUP_ALREADY_SUBMITTED");
    }

    @Test
    void classMeanShiftUsesAnInclusiveTenPointBoundary() {
        assertThat(TeacherGradeService.significantClassMeanShift(70, 60)).isTrue();
        assertThat(TeacherGradeService.significantClassMeanShift(49.99, 60)).isTrue();
        assertThat(TeacherGradeService.significantClassMeanShift(69.99, 60)).isFalse();
        assertThat(TeacherGradeService.significantClassMeanShift(Double.NaN, 60)).isFalse();
    }

    @Test
    void makeupScoreAnomalyExcludesThirtyAndNinetyPointBoundaries() {
        assertThat(TeacherGradeService.makeupScoreAnomaly(new BigDecimal("29.99")))
                .isEqualTo(TeacherGradeService.MakeupScoreAnomaly.LOW);
        assertThat(TeacherGradeService.makeupScoreAnomaly(new BigDecimal("30")))
                .isEqualTo(TeacherGradeService.MakeupScoreAnomaly.NONE);
        assertThat(TeacherGradeService.makeupScoreAnomaly(new BigDecimal("90")))
                .isEqualTo(TeacherGradeService.MakeupScoreAnomaly.NONE);
        assertThat(TeacherGradeService.makeupScoreAnomaly(new BigDecimal("90.01")))
                .isEqualTo(TeacherGradeService.MakeupScoreAnomaly.HIGH);
        assertThat(TeacherGradeService.makeupScoreAnomaly(null))
                .isEqualTo(TeacherGradeService.MakeupScoreAnomaly.NONE);
    }

    @Test
    void partialDraftHistoryWithoutAFinalScoreIsNotAFluctuation() {
        assertThat(TeacherGradeService.significantHistoryFluctuation(null, new BigDecimal("72"))).isFalse();
        assertThat(TeacherGradeService.significantHistoryFluctuation(new BigDecimal("72"), null)).isFalse();
        assertThat(TeacherGradeService.significantHistoryFluctuation(
                new BigDecimal("50"), new BigDecimal("70"))).isFalse();
        assertThat(TeacherGradeService.significantHistoryFluctuation(
                new BigDecimal("49.99"), new BigDecimal("70"))).isTrue();
    }

    @Test
    void historyFluctuationSkipsTheJustSubmittedSnapshotAndUsesThePreviousDistinctScore() {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        ResourceAccessService access = mock(ResourceAccessService.class);
        GradeCryptoService crypto = mock(GradeCryptoService.class);
        when(gateway.select(any(SelectRequest.class))).thenReturn(List.of(
                Map.of("original_ciphertext", "current-ciphertext", "original_nonce", "current-nonce",
                        "original_integrity", "current-integrity"),
                Map.of("original_ciphertext", "previous-ciphertext", "original_nonce", "previous-nonce",
                        "original_integrity", "previous-integrity")));
        when(crypto.decrypt("grade-1", "current-ciphertext", "current-nonce", "current-integrity"))
                .thenReturn("{\"componentScores\":{},\"regularScore\":90,\"finalScore\":90}");
        when(crypto.decrypt("grade-1", "previous-ciphertext", "previous-nonce", "previous-integrity"))
                .thenReturn("{\"componentScores\":{},\"regularScore\":60,\"finalScore\":60}");
        TeacherGradeService service = new TeacherGradeService(gateway, access, crypto,
                new ObjectMapper(), mock(AuditService.class));
        TeacherGradeService.GradeObservation current = new TeacherGradeService.GradeObservation(
                "grade-1", "enrollment-1", new BigDecimal("90"), null, null,
                new BigDecimal("90"), new BigDecimal("90"), Map.of(), null);

        service.historyFluctuation(current);

        verify(gateway).executeAsSystem(argThat(command -> "alerts".equals(command.getTable())
                && "GRADE_HISTORY_FLUCTUATION".equals(command.getValues().get("type"))
                && "grade-1".equals(command.getValues().get("related_id"))));
    }

    @Test
    void studentHistoryFluctuationUsesOnlyClosedOfferingsOwnedByTheTeacher() {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        ResourceAccessService access = mock(ResourceAccessService.class);
        GradeCryptoService crypto = mock(GradeCryptoService.class);
        when(gateway.select(any(SelectRequest.class))).thenReturn(
                List.of(Map.of("id", "enrollment-current", "student_id", "student-1")),
                List.of(Map.of("teacher_id", "teacher-1")),
                List.of(Map.of("id", "offering-closed")),
                List.of(Map.of("id", "enrollment-history", "student_id", "student-1")),
                List.of(Map.of("id", "grade-history", "enrollment_id", "enrollment-history",
                        "score_ciphertext", "ciphertext", "score_nonce", "nonce",
                        "score_integrity", "integrity", "status", "SUBMITTED")));
        when(crypto.decrypt("grade-history", "ciphertext", "nonce", "integrity"))
                .thenReturn("{\"componentScores\":{},\"regularScore\":55,\"finalScore\":55}");
        TeacherGradeService service = new TeacherGradeService(gateway, access, crypto,
                new ObjectMapper(), mock(AuditService.class));
        TeacherGradeService.GradeObservation current = new TeacherGradeService.GradeObservation(
                "grade-current", "enrollment-current", new BigDecimal("80"), null, null,
                new BigDecimal("80"), new BigDecimal("80"), Map.of(), null);

        service.detectStudentHistoryFluctuations("offering-current", List.of(current),
                List.of("grade-current"));

        verify(gateway).executeAsSystem(argThat(command -> "alerts".equals(command.getTable())
                && "STUDENT_HISTORY_FLUCTUATION".equals(command.getValues().get("type"))
                && "grade-current".equals(command.getValues().get("related_id"))));
    }

    @Test
    void statisticsReadsSavedAnalysisEvenWhenNoGradesAreSubmitted() {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        ResourceAccessService access = mock(ResourceAccessService.class);
        when(gateway.select(any(SelectRequest.class))).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            return switch (request.getTable()) {
                case "grade_analyses" -> List.of(Map.of("analysis_text", "freshly loaded analysis"));
                case "grading_schemes" -> List.of(Map.of(
                        "id", "scheme-1", "offering_id", "offering-1", "version", "1"));
                case "enrollments" -> List.of();
                default -> throw new AssertionError("Unexpected table " + request.getTable());
            };
        });
        TeacherGradeService service = gradeService(gateway, access, mock(AuditService.class));

        GradeDtos.Statistics statistics = service.statistics("offering-1");

        assertThat(statistics.count()).isZero();
        assertThat(statistics.savedAnalysis()).isEqualTo("freshly loaded analysis");
    }

    @Test
    void missingReadPermissionIsRejectedBeforeDraftTransactionCanCommit() {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        ResourceAccessService access = mock(ResourceAccessService.class);
        when(access.requirePermission("GRADE_DRAFT_WRITE")).thenReturn(new UserPrincipal(
                "teacher", "teacher", "Teacher", null, Set.of("TEACHER"),
                Set.of("GRADE_DRAFT_WRITE"), true));
        when(access.requirePermission("GRADE_READ")).thenThrow(new ApiException(
                HttpStatus.FORBIDDEN, "PERMISSION_REQUIRED", "missing"));
        TeacherGradeService service = new TeacherGradeService(gateway, access,
                mock(GradeCryptoService.class), new ObjectMapper(), mock(AuditService.class));
        GradeDtos.BatchDraftRequest request = new GradeDtos.BatchDraftRequest("scheme-1", List.of(
                new GradeDtos.GradeEntryInput("enrollment-1", Map.of("DAILY", BigDecimal.TEN), null,
                        GradeDtos.ExamType.REGULAR, 0)), "draft:test");

        assertThatThrownBy(() -> service.saveDrafts("offering-1", request))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("PERMISSION_REQUIRED"));
        verifyNoInteractions(gateway);
    }

    @Test
    void completedDraftReplayReturnsOnlyRequestedRowsInRequestOrderBeforeVersionValidation() {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        ResourceAccessService access = mock(ResourceAccessService.class);
        GradeCryptoService crypto = mock(GradeCryptoService.class);
        when(access.requirePermission(anyString())).thenReturn(teacher("GRADE_DRAFT_WRITE", "GRADE_READ"));
        when(gateway.transactionCompleted(eq("draft:replay"), anyString())).thenReturn(true);
        when(gateway.select(any(SelectRequest.class))).thenAnswer(invocation -> {
            SelectRequest select = invocation.getArgument(0);
            return switch (select.getTable()) {
                case "grading_schemes" -> List.of(Map.of(
                        "id", "scheme-1", "offering_id", "offering-1", "version", "1"));
                case "enrollments" -> List.of(
                        Map.of("id", "enrollment-1", "student_id", "student-1", "status", "ACTIVE"),
                        Map.of("id", "enrollment-2", "student_id", "student-2", "status", "ACTIVE"));
                case "grades" -> List.of(
                        Map.of("id", "grade-1", "enrollment_id", "enrollment-1", "scheme_id", "scheme-1",
                                "score_ciphertext", "cipher-1", "score_nonce", "nonce-1",
                                "score_integrity", "integrity-1", "status", "DRAFT", "version", "2"),
                        Map.of("id", "grade-2", "enrollment_id", "enrollment-2", "scheme_id", "scheme-1",
                                "score_ciphertext", "cipher-2", "score_nonce", "nonce-2",
                                "score_integrity", "integrity-2", "status", "DRAFT", "version", "2"));
                case "students" -> List.of(
                        Map.of("id", "student-1", "student_no", "001", "name", "甲"),
                        Map.of("id", "student-2", "student_no", "002", "name", "乙"));
                default -> throw new AssertionError("Unexpected table " + select.getTable());
            };
        });
        when(crypto.decrypt(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("{\"componentScores\":{\"FINAL\":80},\"regularScore\":80,\"finalScore\":80}");
        TeacherGradeService service = new TeacherGradeService(gateway, access, crypto,
                new ObjectMapper(), mock(AuditService.class));
        GradeDtos.BatchDraftRequest request = new GradeDtos.BatchDraftRequest("scheme-1", List.of(
                new GradeDtos.GradeEntryInput("enrollment-2", Map.of("FINAL", new BigDecimal("80")),
                        null, GradeDtos.ExamType.REGULAR, 1),
                new GradeDtos.GradeEntryInput("enrollment-1", Map.of("FINAL", new BigDecimal("80")),
                        null, GradeDtos.ExamType.REGULAR, 1)), "draft:replay");

        List<GradeDtos.GradeView> replay = service.saveDrafts("offering-1", request);

        assertThat(replay).extracting(GradeDtos.GradeView::enrollmentId)
                .containsExactly("enrollment-2", "enrollment-1");
        verify(gateway, never()).transaction(any());
    }

    @Test
    void completedSubmitReplaySkipsGradeStateValidationAndDuplicateAnomalyWork() {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        ResourceAccessService access = mock(ResourceAccessService.class);
        when(access.requirePermission("GRADE_SUBMIT")).thenReturn(teacher("GRADE_SUBMIT"));
        when(gateway.transactionCompleted(eq("submit:replay"), anyString())).thenReturn(true);
        TeacherGradeService service = gradeService(gateway, access, mock(AuditService.class));
        GradeDtos.BatchActionRequest request = new GradeDtos.BatchActionRequest(
                List.of("grade-1"), GradeDtos.ExamType.REGULAR,
                "教师确认提交正考成绩", "submit:replay");

        service.submit("offering-1", request);

        verify(gateway, never()).select(any());
        verify(gateway, never()).transaction(any());
    }

    @Test
    void courseHistoryDoesNotRequireGradeReadPermissionInternally() {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        ResourceAccessService access = mock(ResourceAccessService.class);
        when(access.requirePermission("GRADE_HISTORY_READ")).thenReturn(new UserPrincipal(
                "teacher-user", "teacher", "Teacher", null, Set.of("TEACHER"),
                Set.of("GRADE_HISTORY_READ"), true));
        when(gateway.count(any(SelectRequest.class))).thenReturn(0L);
        when(gateway.select(any(SelectRequest.class))).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            return switch (request.getTable()) {
                case "teachers" -> List.of(Map.of("id", "teacher-1"));
                case "teacher_course_historical_grades" -> List.of();
                default -> throw new AssertionError("Unexpected table " + request.getTable());
            };
        });
        TeacherGradeService service = new TeacherGradeService(gateway, access,
                mock(GradeCryptoService.class), new ObjectMapper(), mock(AuditService.class));

        assertThat(service.courseHistory("course-1", null, 0, 20).items()).isEmpty();

        verify(access).requirePermission("GRADE_HISTORY_READ");
        verify(access, never()).requireTeachingOffering(anyString(), eq("GRADE_READ"));
    }

    @Test
    void courseHistoryUsesDatabasePagingAndAnExactUntruncatedTotal() {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        ResourceAccessService access = mock(ResourceAccessService.class);
        GradeCryptoService crypto = mock(GradeCryptoService.class);
        when(access.requirePermission("GRADE_HISTORY_READ")).thenReturn(new UserPrincipal(
                "teacher-user", "teacher", "Teacher", null, Set.of("TEACHER"),
                Set.of("GRADE_HISTORY_READ"), true));
        when(gateway.count(any(SelectRequest.class))).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            assertThat(request.getTable()).isEqualTo("teacher_course_historical_grades");
            assertThat(request.getFilters().stream().map(filter -> filter.getColumn()).toList())
                    .containsExactly("course_id", "teacher_id", "academic_year");
            return 1001L;
        });
        when(gateway.select(any(SelectRequest.class))).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            if ("teachers".equals(request.getTable())) return List.of(Map.of("id", "teacher-1"));
            assertThat(request.getTable()).isEqualTo("teacher_course_historical_grades");
            assertThat(request.getPage()).isEqualTo(40);
            assertThat(request.getPageSize()).isEqualTo(25);
            return List.of(Map.of("id", "grade-history", "score_ciphertext", "ciphertext",
                    "score_nonce", "nonce", "score_integrity", "integrity", "student_id", "student-1",
                    "student_no", "20260001", "student_name", "学生甲", "offering_id", "offering-closed",
                    "academic_year", "2025-2026", "semester", "2"));
        });
        when(crypto.decrypt("grade-history", "ciphertext", "nonce", "integrity"))
                .thenReturn("{\"componentScores\":{},\"regularScore\":52,\"makeupRawScore\":80,"
                        + "\"makeupEffectiveScore\":60,\"finalScore\":60,\"makeupStatus\":\"SUBMITTED\"}");
        TeacherGradeService service = new TeacherGradeService(gateway, access, crypto,
                new ObjectMapper(), mock(AuditService.class));

        var history = service.courseHistory("course-1", "2025-2026", 40, 25);

        assertThat(history.total()).isEqualTo(1001);
        assertThat(history.totalPages()).isEqualTo(41);
        assertThat(history.items()).singleElement().satisfies(grade -> {
            assertThat(grade.studentNo()).isEqualTo("20260001");
            assertThat(grade.makeupRawScore()).isEqualByComparingTo("80");
            assertThat(grade.finalScore()).isEqualByComparingTo("60");
        });
    }

    @Test
    void saveDraftsAuditsRmiDenialForCallerSuppliedEnrollment() {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        ResourceAccessService access = mock(ResourceAccessService.class);
        AuditService audit = mock(AuditService.class);
        when(access.requirePermission(anyString())).thenReturn(teacher("GRADE_DRAFT_WRITE", "GRADE_READ"));
        ApiException denial = rmiAccessDenied();
        when(gateway.select(any(SelectRequest.class))).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            return switch (request.getTable()) {
                case "grading_schemes" -> List.of(Map.of(
                        "id", "scheme-1", "offering_id", "offering-1", "version", "1"));
                case "grading_weights" -> List.of(
                        Map.of("item_code", "DAILY", "weight", "30", "max_score", "100"),
                        Map.of("item_code", "LAB", "weight", "20", "max_score", "100"),
                        Map.of("item_code", "FINAL", "weight", "50", "max_score", "100"));
                case "enrollments" -> throw denial;
                default -> throw new AssertionError("Unexpected table " + request.getTable());
            };
        });
        TeacherGradeService service = gradeService(gateway, access, audit);
        GradeDtos.BatchDraftRequest request = new GradeDtos.BatchDraftRequest("scheme-1", List.of(
                new GradeDtos.GradeEntryInput("foreign-enrollment", Map.of("DAILY", BigDecimal.TEN),
                        null, GradeDtos.ExamType.REGULAR, 0)), "draft:foreign");

        assertThatThrownBy(() -> service.saveDrafts("offering-1", request)).isSameAs(denial);

        verify(audit).ownershipDenied("enrollments", "foreign-enrollment");
    }

    @Test
    void submitAuditsRmiDenialForCallerSuppliedGrade() {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        ResourceAccessService access = mock(ResourceAccessService.class);
        AuditService audit = mock(AuditService.class);
        when(access.requirePermission("GRADE_SUBMIT")).thenReturn(teacher("GRADE_SUBMIT"));
        ApiException denial = rmiAccessDenied();
        when(gateway.select(any(SelectRequest.class))).thenThrow(denial);
        TeacherGradeService service = gradeService(gateway, access, audit);
        GradeDtos.BatchActionRequest request = new GradeDtos.BatchActionRequest(
                List.of("foreign-grade"), GradeDtos.ExamType.REGULAR,
                "submit persisted draft", "submit:foreign");

        assertThatThrownBy(() -> service.submit("offering-1", request)).isSameAs(denial);

        verify(audit).ownershipDenied("grades", "foreign-grade");
    }

    @Test
    void historyAuditsRmiDenialForCallerSuppliedGrade() {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        ResourceAccessService access = mock(ResourceAccessService.class);
        AuditService audit = mock(AuditService.class);
        ApiException denial = rmiAccessDenied();
        when(gateway.select(any(SelectRequest.class))).thenThrow(denial);
        TeacherGradeService service = gradeService(gateway, access, audit);

        assertThatThrownBy(() -> service.history("offering-1", "foreign-grade", 0, 20))
                .isSameAs(denial);

        verify(audit).ownershipDenied("grades", "foreign-grade");
    }

    private TeacherGradeService gradeService(RemoteDataGateway gateway, ResourceAccessService access,
                                             AuditService audit) {
        return new TeacherGradeService(gateway, access, mock(GradeCryptoService.class),
                new ObjectMapper(), audit);
    }

    private void assertNullRetakeRejectedWithoutTransaction(String existingPayload, String expectedCode) {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        ResourceAccessService access = mock(ResourceAccessService.class);
        GradeCryptoService crypto = mock(GradeCryptoService.class);
        when(access.requirePermission(anyString())).thenReturn(teacher("GRADE_DRAFT_WRITE", "GRADE_READ"));
        when(gateway.select(any(SelectRequest.class))).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            return switch (request.getTable()) {
                case "grading_schemes" -> List.of(Map.of(
                        "id", "scheme-1", "offering_id", "offering-1", "version", "1"));
                case "grading_weights" -> List.of(
                        Map.of("item_code", "DAILY", "weight", "30", "max_score", "100"),
                        Map.of("item_code", "LAB", "weight", "20", "max_score", "100"),
                        Map.of("item_code", "FINAL", "weight", "50", "max_score", "100"));
                case "enrollments" -> List.of(Map.of(
                        "id", "enrollment-1", "student_id", "student-1"));
                case "students" -> List.of(Map.of(
                        "id", "student-1", "student_no", "001", "name", "甲"));
                case "grades" -> List.of(Map.of(
                        "id", "grade-1", "enrollment_id", "enrollment-1", "scheme_id", "scheme-1",
                        "score_ciphertext", "old-cipher", "score_nonce", "old-nonce",
                        "score_integrity", "old-integrity", "status", "SUBMITTED", "version", "7"));
                default -> throw new AssertionError("Unexpected table " + request.getTable());
            };
        });
        when(crypto.decrypt("grade-1", "old-cipher", "old-nonce", "old-integrity"))
                .thenReturn(existingPayload);
        TeacherGradeService service = new TeacherGradeService(gateway, access, crypto,
                new ObjectMapper(), mock(AuditService.class));
        GradeDtos.BatchDraftRequest request = new GradeDtos.BatchDraftRequest("scheme-1", List.of(
                new GradeDtos.GradeEntryInput("enrollment-1", Map.of(), null,
                        GradeDtos.ExamType.RETAKE, 7)), "draft:invalid-clear");

        assertThatThrownBy(() -> service.saveDrafts("offering-1", request))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo(expectedCode));
        verify(gateway, never()).transaction(any());
        verify(crypto, never()).encrypt(anyString(), anyString());
    }

    private UserPrincipal teacher(String... permissions) {
        return new UserPrincipal("teacher", "teacher", "Teacher", null,
                Set.of("TEACHER"), Set.of(permissions), true);
    }

    private ApiException rmiAccessDenied() {
        return new ApiException(HttpStatus.FORBIDDEN, "RMI_ACCESS_DENIED", "access denied");
    }
}
