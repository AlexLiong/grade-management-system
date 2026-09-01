package edu.chd.practice.web.rmi;

import edu.chd.practice.rmi.contract.ManipulationInterface;
import edu.chd.practice.rmi.contract.SelectInterface;
import edu.chd.practice.rmi.contract.dto.InvocationContext;
import edu.chd.practice.rmi.contract.dto.MutationCommand;
import edu.chd.practice.rmi.contract.dto.MutationType;
import edu.chd.practice.rmi.contract.dto.SelectRequest;
import edu.chd.practice.web.config.RmiProperties;
import edu.chd.practice.web.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.file.Path;
import java.rmi.RemoteException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteDataGatewayTest {
    private RmiStubProvider stubs;
    private RmiInvocationSigner signer;
    private SelectInterface select;
    private RemoteDataGateway gateway;
    private SelectRequest request;

    @BeforeEach
    void setUp() {
        stubs = mock(RmiStubProvider.class);
        signer = mock(RmiInvocationSigner.class);
        select = mock(SelectInterface.class);
        gateway = new RemoteDataGateway(stubs, signer);
        request = new SelectRequest("users", List.of("id"), List.of(), List.of(), 0, 1);
        when(stubs.select()).thenReturn(select);
        when(signer.sign(any(), any())).thenReturn(mock(InvocationContext.class));
        when(signer.signAsGateway(any(), any())).thenReturn(mock(InvocationContext.class));
    }

    @Test
    void invalidatesCachedStubWhenSelectFailsRemotely() throws Exception {
        when(select.select(any(), any())).thenThrow(new RemoteException("stale stub"));

        assertUnavailable(() -> gateway.select(request));

        verify(stubs).invalidate();
    }

    @Test
    void invalidatesCachedStubWhenUserOrGatewayCountFailsRemotely() throws Exception {
        when(select.count(any(), any())).thenThrow(new RemoteException("stale stub"));

        assertUnavailable(() -> gateway.count(request));
        assertUnavailable(() -> gateway.countAsGateway(request));

        verify(stubs, org.mockito.Mockito.times(2)).invalidate();
    }

    @Test
    void mapsStableRemoteBusinessCodesToTheirHttpSemantics() {
        assertThat(RemoteDataGateway.statusForRemoteCode("IDEMPOTENCY_CONFLICT")).isEqualTo(HttpStatus.CONFLICT);
        assertThat(RemoteDataGateway.statusForRemoteCode("NO_ROWS_AFFECTED")).isEqualTo(HttpStatus.CONFLICT);
        assertThat(RemoteDataGateway.statusForRemoteCode("MAKEUP_TRANSITION_INVALID")).isEqualTo(HttpStatus.CONFLICT);
        assertThat(RemoteDataGateway.statusForRemoteCode("SUBMITTED_GRADE_IMMUTABLE")).isEqualTo(HttpStatus.CONFLICT);
        assertThat(RemoteDataGateway.statusForRemoteCode("GRADE_INTEGRITY_INVALID")).isEqualTo(HttpStatus.CONFLICT);
        assertThat(RemoteDataGateway.statusForRemoteCode("APPROVAL_CONFLICT")).isEqualTo(HttpStatus.CONFLICT);

        assertThat(RemoteDataGateway.statusForRemoteCode("TRANSACTION_SIZE_INVALID")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(RemoteDataGateway.statusForRemoteCode("IDEMPOTENCY_KEY_INVALID")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(RemoteDataGateway.statusForRemoteCode("IDEMPOTENCY_FINGERPRINT_INVALID"))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(RemoteDataGateway.statusForRemoteCode("GRADE_PAYLOAD_INVALID")).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(RemoteDataGateway.statusForRemoteCode("APPROVAL_REQUIRED")).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(RemoteDataGateway.statusForRemoteCode("APPROVAL_INVALID")).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(RemoteDataGateway.statusForRemoteCode("GRADE_NOT_FOUND")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(RemoteDataGateway.statusForRemoteCode("AUTH_SIGNATURE_INVALID")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(RemoteDataGateway.statusForRemoteCode("SELECT_FAILED")).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void distinguishesAuditAndAlertWritesWithinOneHttpTraceWhileKeepingReplaysStable() throws Exception {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        RmiInvocationSigner actualSigner = new RmiInvocationSigner(new RmiProperties(
                "127.0.0.1", 1199, false, "test-secret-material-with-more-than-32-bytes!",
                Path.of("unused-test-key"), Duration.ofSeconds(1)), environment);
        ManipulationInterface manipulation = mock(ManipulationInterface.class);
        when(stubs.manipulation()).thenReturn(manipulation);
        when(manipulation.execute(any(), any())).thenReturn(true);
        RemoteDataGateway actualGateway = new RemoteDataGateway(stubs, actualSigner);
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setAttribute("traceId", "trace-12345678");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(httpRequest));
        MutationCommand auditLog = new MutationCommand(MutationType.INSERT, "audit_logs",
                Map.of("id", "audit-1", "request_id", "trace-12345678"), List.of());
        MutationCommand alert = new MutationCommand(MutationType.INSERT, "alerts",
                Map.of("id", "alert-1", "status", "OPEN"), List.of());

        try {
            actualGateway.executeAsSystem(auditLog);
            actualGateway.executeAsSystem(alert);
            actualGateway.executeAsSystem(auditLog);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        ArgumentCaptor<InvocationContext> contexts = ArgumentCaptor.forClass(InvocationContext.class);
        verify(manipulation, times(3)).execute(any(), contexts.capture());
        assertThat(contexts.getAllValues()).extracting(InvocationContext::getRequestId)
                .allMatch(value -> value.matches("[0-9a-f]{64}"));
        assertThat(contexts.getAllValues().get(0).getRequestId())
                .isNotEqualTo(contexts.getAllValues().get(1).getRequestId())
                .isEqualTo(contexts.getAllValues().get(2).getRequestId());
    }

    private void assertUnavailable(Runnable invocation) {
        assertThatThrownBy(invocation::run).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status().value()).isEqualTo(503);
            assertThat(error.code()).isEqualTo("RMI_UNAVAILABLE");
        });
    }
}
