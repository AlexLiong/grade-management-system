package edu.chd.practice.web.rmi;

import edu.chd.practice.rmi.contract.IntegrityInterface;
import edu.chd.practice.rmi.contract.ManipulationInterface;
import edu.chd.practice.rmi.contract.RemoteServiceException;
import edu.chd.practice.rmi.contract.SelectInterface;
import edu.chd.practice.rmi.contract.dto.HealthStatus;
import edu.chd.practice.rmi.contract.dto.IntegrityReport;
import edu.chd.practice.rmi.contract.dto.IdempotencyProbe;
import edu.chd.practice.rmi.contract.dto.MutationCommand;
import edu.chd.practice.rmi.contract.dto.RecoveryQuery;
import edu.chd.practice.rmi.contract.dto.RecoveryRecord;
import edu.chd.practice.rmi.contract.dto.RecoveryEvidenceRequest;
import edu.chd.practice.rmi.contract.dto.RecoveryRestoreRequest;
import edu.chd.practice.rmi.contract.dto.RecoverySnapshot;
import edu.chd.practice.rmi.contract.dto.SelectRequest;
import edu.chd.practice.rmi.contract.dto.TransactionRequest;
import edu.chd.practice.web.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class RemoteDataGateway {
    private final RmiStubProvider stubs;
    private final RmiInvocationSigner signer;

    public RemoteDataGateway(RmiStubProvider stubs, RmiInvocationSigner signer) {
        this.stubs = stubs;
        this.signer = signer;
    }

    public List<Map<String, String>> select(SelectRequest request) {
        return select(request, false);
    }

    public List<Map<String, String>> selectAsGateway(SelectRequest request) {
        return select(request, true);
    }

    public List<Map<String, String>> selectAsAnalytics(SelectRequest request) {
        if (!List.of("course_offerings", "enrollments", "grades").contains(request.getTable())) {
            throw new IllegalArgumentException("ANALYTICS context is restricted to model-training tables");
        }
        return selectInternal(request, InternalRole.ANALYTICS);
    }

    private List<Map<String, String>> select(SelectRequest request, boolean internalGateway) {
        return selectInternal(request, internalGateway ? InternalRole.GATEWAY : InternalRole.NONE);
    }

    private List<Map<String, String>> selectInternal(SelectRequest request, InternalRole role) {
        try {
            var context = switch (role) {
                case GATEWAY -> signer.signAsGateway(SelectInterface.OP_SELECT, request);
                case ANALYTICS -> signer.signAsAnalytics(SelectInterface.OP_SELECT, request);
                case NONE -> signer.sign(SelectInterface.OP_SELECT, request);
            };
            String[][] rows = stubs.select().select(request, context);
            List<Map<String, String>> result = new ArrayList<>(rows.length);
            for (String[] row : rows) {
                if (row.length != request.getColumns().size()) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "RMI_PROTOCOL_ERROR",
                            "RMI 返回列数与请求不一致");
                }
                Map<String, String> mapped = new LinkedHashMap<>();
                for (int index = 0; index < row.length; index++) {
                    mapped.put(request.getColumns().get(index), row[index]);
                }
                result.add(mapped);
            }
            return result;
        } catch (RemoteException exception) {
            stubs.invalidate();
            throw remoteUnavailable(exception);
        } catch (RemoteServiceException exception) {
            throw remoteRejected(exception);
        }
    }

    public long count(SelectRequest request) {
        try {
            return stubs.select().count(request, signer.sign(SelectInterface.OP_COUNT, request));
        } catch (RemoteException exception) {
            stubs.invalidate();
            throw remoteUnavailable(exception);
        } catch (RemoteServiceException exception) {
            throw remoteRejected(exception);
        }
    }

    public long countAsGateway(SelectRequest request) {
        try {
            return stubs.select().count(request, signer.signAsGateway(SelectInterface.OP_COUNT, request));
        } catch (RemoteException exception) {
            stubs.invalidate();
            throw remoteUnavailable(exception);
        } catch (RemoteServiceException exception) {
            throw remoteRejected(exception);
        }
    }

    public void execute(MutationCommand command) {
        execute(command, false);
    }

    public void executeAsSystem(MutationCommand command) {
        if (!"audit_logs".equals(command.getTable()) && !"alerts".equals(command.getTable())) {
            throw new IllegalArgumentException("SYSTEM context is restricted to security telemetry tables");
        }
        execute(command, true);
    }

    private void execute(MutationCommand command, boolean internalSystem) {
        try {
            if (!stubs.manipulation().execute(command,
                    internalSystem ? signer.signAsSystem(ManipulationInterface.OP_EXECUTE, command)
                            : signer.sign(ManipulationInterface.OP_EXECUTE, command))) {
                throw new ApiException(HttpStatus.CONFLICT, "REMOTE_WRITE_REJECTED", "远程数据服务拒绝写入");
            }
        } catch (RemoteException exception) {
            stubs.invalidate();
            throw remoteUnavailable(exception);
        } catch (RemoteServiceException exception) {
            throw remoteRejected(exception);
        }
    }

    public void transaction(TransactionRequest request) {
        try {
            if (!stubs.manipulation().executeTransaction(request,
                    signer.sign(ManipulationInterface.OP_TRANSACTION, request))) {
                throw new ApiException(HttpStatus.CONFLICT, "REMOTE_TRANSACTION_REJECTED", "远程事务未提交");
            }
        } catch (RemoteException exception) {
            stubs.invalidate();
            throw remoteUnavailable(exception);
        } catch (RemoteServiceException exception) {
            throw remoteRejected(exception);
        }
    }

    public boolean transactionCompleted(String idempotencyKey, String semanticFingerprint) {
        IdempotencyProbe probe = new IdempotencyProbe(idempotencyKey, semanticFingerprint);
        try {
            return stubs.manipulation().transactionCompleted(probe,
                    signer.sign(ManipulationInterface.OP_IDEMPOTENCY_PROBE, probe));
        } catch (RemoteException exception) {
            stubs.invalidate();
            throw remoteUnavailable(exception);
        } catch (RemoteServiceException exception) {
            throw remoteRejected(exception);
        }
    }

    public HealthStatus health() {
        try {
            return stubs.health().check();
        } catch (RemoteException exception) {
            stubs.invalidate();
            throw remoteUnavailable(exception);
        }
    }

    public IntegrityReport verifyLedger() {
        try {
            return stubs.integrity().verifyLedger(signer.sign(IntegrityInterface.OP_VERIFY, null));
        } catch (RemoteException exception) {
            stubs.invalidate();
            throw remoteUnavailable(exception);
        } catch (RemoteServiceException exception) {
            throw remoteRejected(exception);
        }
    }

    public List<RecoveryRecord> recovery(RecoveryQuery query) {
        try {
            return List.of(stubs.integrity().readRecoveryEvidence(query,
                    signer.sign(IntegrityInterface.OP_RECOVERY, query)));
        } catch (RemoteException exception) {
            stubs.invalidate();
            throw remoteUnavailable(exception);
        } catch (RemoteServiceException exception) {
            throw remoteRejected(exception);
        }
    }

    public RecoverySnapshot decryptRecovery(RecoveryEvidenceRequest request) {
        try {
            return stubs.integrity().decryptRecoveryEvidence(request,
                    signer.sign(IntegrityInterface.OP_DECRYPT_RECOVERY, request));
        } catch (RemoteException exception) {
            stubs.invalidate();
            throw remoteUnavailable(exception);
        } catch (RemoteServiceException exception) {
            throw remoteRejected(exception);
        }
    }

    public void restoreGrade(RecoveryRestoreRequest request) {
        try {
            if (!stubs.integrity().restoreGrade(request,
                    signer.sign(IntegrityInterface.OP_RESTORE, request))) {
                throw new ApiException(HttpStatus.CONFLICT, "RECOVERY_REJECTED", "原始成绩恢复未执行");
            }
        } catch (RemoteException exception) {
            stubs.invalidate();
            throw remoteUnavailable(exception);
        } catch (RemoteServiceException exception) {
            throw remoteRejected(exception);
        }
    }

    private ApiException remoteUnavailable(RemoteException exception) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RMI_UNAVAILABLE",
                "RMI 数据服务暂时不可用", exception);
    }

    private ApiException remoteRejected(RemoteServiceException exception) {
        HttpStatus status = statusForRemoteCode(exception.getCode());
        return new ApiException(status, "RMI_" + exception.getCode(), exception.getMessage(), exception);
    }

    static HttpStatus statusForRemoteCode(String code) {
        if (code == null) {
            return HttpStatus.BAD_GATEWAY;
        }
        if ("NOT_FOUND".equals(code) || code.endsWith("_NOT_FOUND")) {
            return HttpStatus.NOT_FOUND;
        }
        if (Set.of("AUTHENTICATION_REQUIRED", "AUTH_CONTEXT_INVALID", "AUTH_SIGNATURE_INVALID",
                "AUTH_TIMESTAMP_EXPIRED", "AUTH_REPLAY").contains(code)) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (Set.of("ACCESS_DENIED", "APPROVAL_REQUIRED", "APPROVAL_INVALID").contains(code)) {
            return HttpStatus.FORBIDDEN;
        }
        if (Set.of("DUPLICATE", "CONFLICT", "NO_ROWS_AFFECTED", "SNAPSHOT_MISMATCH",
                "TRANSACTION_ROLLED_BACK", "RECOVERY_ROLLED_BACK", "GRADE_INTEGRITY_INVALID",
                "APPROVAL_CONFLICT", "LAST_CAPABLE_ADMIN_REQUIRED").contains(code)
                || code.endsWith("_CONFLICT") || code.endsWith("_STATUS_INVALID")
                || code.endsWith("_TRANSITION_INVALID") || code.endsWith("_IMMUTABLE")) {
            return HttpStatus.CONFLICT;
        }
        if (Set.of("INVALID_REQUEST", "VALIDATION_FAILED", "SELECT_INVALID", "SCHEMA_NOT_ALLOWED",
                "MUTATION_INVALID").contains(code)
                || code.endsWith("_INVALID") || code.endsWith("_REQUIRED")
                || code.endsWith("_INCOMPLETE") || code.endsWith("_LIMIT")) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.BAD_GATEWAY;
    }

    private enum InternalRole { NONE, GATEWAY, ANALYTICS }
}
