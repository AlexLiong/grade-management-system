package edu.chd.practice.web.service;

import edu.chd.practice.rmi.contract.dto.SelectRequest;
import edu.chd.practice.web.dto.GradeDtos;
import edu.chd.practice.web.error.ApiException;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import edu.chd.practice.web.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeacherCourseServiceWeightTest {
    private RemoteDataGateway gateway;
    private ResourceAccessService access;
    private TeacherCourseService service;

    @BeforeEach
    void setUp() {
        gateway = mock(RemoteDataGateway.class);
        access = mock(ResourceAccessService.class);
        when(access.requirePermission("GRADING_SCHEME_WRITE")).thenReturn(new UserPrincipal(
                "teacher-user", "teacher", "Teacher", null, Set.of("TEACHER"),
                Set.of("GRADING_SCHEME_WRITE"), true));
        service = new TeacherCourseService(gateway, access, mock(AuditService.class));
    }

    @Test
    void savesUniqueHundredPointItemsAndBuildsResponseWithoutPostCommitRead() {
        when(gateway.select(any())).thenReturn(List.of());
        GradeDtos.SaveWeightsRequest request = request(List.of(
                item("DAILY", "平时", "40", "100", 1),
                item("FINAL", "期末", "60", "100", 2)));

        GradeDtos.WeightScheme saved = service.saveWeights("offering-1", request);

        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.totalWeight()).isEqualByComparingTo("100");
        assertThat(saved.items()).extracting(GradeDtos.WeightItem::itemCode)
                .containsExactly("DAILY", "FINAL");
        assertThat(saved.items()).allSatisfy(item -> {
            assertThat(item.id()).isNotBlank();
            assertThat(item.maxScore()).isEqualByComparingTo("100");
        });
        verify(gateway).select(any(SelectRequest.class));
        verify(gateway).transaction(any());
    }

    @Test
    void rejectsDuplicateCodesAndInvalidTotalOrMaximum() {
        assertCode(request(List.of(
                item("SAME", "A", "50", "100", 1),
                item("SAME", "B", "50", "100", 2))), "DUPLICATE_WEIGHT_ITEM");
        assertCode(request(List.of(item("ONLY", "Only", "99", "100", 1))), "WEIGHT_TOTAL_INVALID");
        assertCode(request(List.of(item("ONLY", "Only", "100", "99", 1))), "WEIGHT_MAX_SCORE_INVALID");
        assertCode(request(List.of(item("lowercase", "Only", "100", "100", 1))), "WEIGHT_ITEM_INVALID");
        assertCode(request(List.of(
                item("FIRST", "First", "50", "100", 1),
                item("SECOND", "Second", "50", "100", 1))), "WEIGHT_SORT_ORDER_INVALID");
        verify(gateway, never()).transaction(any());
    }

    @Test
    void existingGradesPreventRemovingOrRenamingPersistedItemCodes() {
        when(gateway.select(any())).thenAnswer(invocation -> {
            SelectRequest select = invocation.getArgument(0);
            return switch (select.getTable()) {
                case "grading_schemes" -> List.of(Map.of(
                        "id", "scheme-1", "version", "3", "status", "ACTIVE"));
                case "grading_weights" -> List.of(
                        Map.of("item_code", "DAILY"), Map.of("item_code", "FINAL"));
                default -> throw new AssertionError("Unexpected table " + select.getTable());
            };
        });
        when(gateway.count(any())).thenReturn(0L, 1L);

        assertCode(new GradeDtos.SaveWeightsRequest("方案", List.of(
                item("DAILY", "平时", "40", "100", 1),
                item("EXAM", "考试", "60", "100", 2)), 3), "WEIGHT_ITEMS_LOCKED_BY_GRADES");

        verify(gateway, never()).transaction(any());
    }

    @Test
    void submittedGradesFreezeTheEntireSchemeBeforeStartingATransaction() {
        when(gateway.select(any())).thenReturn(List.of(Map.of(
                "id", "scheme-1", "version", "3", "status", "ACTIVE")));
        when(gateway.count(any())).thenReturn(1L);

        assertCode(new GradeDtos.SaveWeightsRequest("新方案名称", List.of(
                item("DAILY", "新平时名称", "30", "100", 1),
                item("FINAL", "期末", "70", "100", 2)), 3),
                "GRADING_SCHEME_LOCKED_BY_SUBMITTED_GRADES");

        verify(gateway, never()).transaction(any());
    }

    private void assertCode(GradeDtos.SaveWeightsRequest request, String code) {
        assertThatThrownBy(() -> service.saveWeights("offering-1", request))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(code));
    }

    private GradeDtos.SaveWeightsRequest request(List<GradeDtos.WeightItem> items) {
        return new GradeDtos.SaveWeightsRequest("方案", items, 0);
    }

    private GradeDtos.WeightItem item(String code, String name, String weight, String maximum, int order) {
        return new GradeDtos.WeightItem(null, code, name, new BigDecimal(weight),
                new BigDecimal(maximum), order);
    }
}
