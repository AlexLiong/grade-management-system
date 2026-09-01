package edu.chd.practice.rmi.server.service;

import edu.chd.practice.rmi.contract.IntegrityInterface;
import edu.chd.practice.rmi.contract.RemoteServiceException;
import edu.chd.practice.rmi.contract.dto.IntegrityReport;
import edu.chd.practice.rmi.contract.dto.InvocationContext;
import edu.chd.practice.rmi.contract.dto.RecoveryEvidenceRequest;
import edu.chd.practice.rmi.contract.dto.RecoveryQuery;
import edu.chd.practice.rmi.contract.dto.RecoveryRecord;
import edu.chd.practice.rmi.contract.dto.RecoveryRestoreRequest;
import edu.chd.practice.rmi.contract.dto.RecoverySnapshot;
import edu.chd.practice.rmi.server.integrity.HashChainLedger;
import edu.chd.practice.rmi.server.security.AuthorizationService;
import edu.chd.practice.rmi.server.security.RequestAuthenticator;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class IntegrityRemoteService implements IntegrityInterface {
    private final RequestAuthenticator authenticator;
    private final AuthorizationService authorization;
    private final HashChainLedger ledger;
    private final GradeRecoveryService recovery;

    public IntegrityRemoteService(RequestAuthenticator authenticator, AuthorizationService authorization,
                                  HashChainLedger ledger, GradeRecoveryService recovery) {
        this.authenticator = authenticator;
        this.authorization = authorization;
        this.ledger = ledger;
        this.recovery = recovery;
    }

    @Override
    public IntegrityReport verifyLedger(InvocationContext context) throws RemoteServiceException {
        requireFresh(authenticator.authenticate(OP_VERIFY, null, context));
        authorization.requireIntegrityAccess(context);
        authorization.requirePermission(context, "INTEGRITY_VERIFY");
        return ledger.verify();
    }

    @Override
    public RecoveryRecord[] readRecoveryEvidence(RecoveryQuery query, InvocationContext context)
            throws RemoteServiceException {
        requireFresh(authenticator.authenticate(OP_RECOVERY, query, context));
        authorization.requireIntegrityAccess(context);
        authorization.requirePermission(context, "GRADE_RESTORE_ORIGINAL");
        if (!"GRADE".equals(query.getAggregateType())) {
            throw new RemoteServiceException("RECOVERY_QUERY_INVALID", "Only GRADE recovery evidence is available");
        }
        try {
            return ledger.read(query);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new RemoteServiceException("RECOVERY_QUERY_INVALID", exception.getMessage());
        }
    }

    @Override
    public RecoverySnapshot decryptRecoveryEvidence(RecoveryEvidenceRequest request, InvocationContext context)
            throws RemoteServiceException {
        requireFresh(authenticator.authenticate(OP_DECRYPT_RECOVERY, request, context));
        requireRecoveryPermission(context, request.getReason());
        try {
            return recovery.decrypt(request.getSequence());
        } catch (RuntimeException exception) {
            throw new RemoteServiceException("RECOVERY_DECRYPT_FAILED", "Recovery evidence could not be authenticated");
        }
    }

    @Override
    public boolean restoreGrade(RecoveryRestoreRequest request, InvocationContext context)
            throws RemoteServiceException {
        requireFresh(authenticator.authenticate(OP_RESTORE, request, context));
        requireRecoveryPermission(context, request.getReason());
        if (request.getApprovalId() == null || request.getApprovalId().isBlank()) {
            throw new RemoteServiceException("APPROVAL_REQUIRED", "Grade recovery requires dual-person approval");
        }
        try {
            return recovery.restore(request.getSequence(), request.getReason(), request.getApprovalId(), context);
        } catch (DataAccessException exception) {
            throw new RemoteServiceException("RECOVERY_ROLLED_BACK", "Database rejected the grade recovery transaction");
        } catch (RuntimeException exception) {
            throw new RemoteServiceException("RECOVERY_ROLLED_BACK", "Grade recovery was rolled back");
        }
    }

    private void requireRecoveryPermission(InvocationContext context, String reason) throws RemoteServiceException {
        if (reason == null || reason.isBlank() || reason.length() > 1024) {
            throw new RemoteServiceException("RECOVERY_REASON_REQUIRED", "Recovery access requires a reason");
        }
        Set<String> roles = authorization.effectiveRoles(context);
        if (!roles.contains("ADMIN")) {
            throw new RemoteServiceException("ACCESS_DENIED", "Only ADMIN may decrypt or restore grade evidence");
        }
        authorization.requirePermission(context, "GRADE_RESTORE_ORIGINAL");
    }

    private static void requireFresh(boolean fresh) throws RemoteServiceException {
        if (!fresh) throw new RemoteServiceException("AUTH_REPLAY", "Invocation nonce has already been used");
    }
}
