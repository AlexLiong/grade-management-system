package edu.chd.practice.rmi.contract;

import edu.chd.practice.rmi.contract.dto.IntegrityReport;
import edu.chd.practice.rmi.contract.dto.InvocationContext;
import edu.chd.practice.rmi.contract.dto.RecoveryQuery;
import edu.chd.practice.rmi.contract.dto.RecoveryRecord;
import edu.chd.practice.rmi.contract.dto.RecoveryEvidenceRequest;
import edu.chd.practice.rmi.contract.dto.RecoveryRestoreRequest;
import edu.chd.practice.rmi.contract.dto.RecoverySnapshot;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface IntegrityInterface extends Remote {
    String OP_VERIFY = "integrity.verify";
    String OP_RECOVERY = "integrity.recovery";
    String OP_DECRYPT_RECOVERY = "integrity.decrypt-recovery";
    String OP_RESTORE = "integrity.restore";

    IntegrityReport verifyLedger(InvocationContext context)
            throws RemoteException, RemoteServiceException;

    RecoveryRecord[] readRecoveryEvidence(RecoveryQuery query, InvocationContext context)
            throws RemoteException, RemoteServiceException;

    RecoverySnapshot decryptRecoveryEvidence(RecoveryEvidenceRequest request, InvocationContext context)
            throws RemoteException, RemoteServiceException;

    boolean restoreGrade(RecoveryRestoreRequest request, InvocationContext context)
            throws RemoteException, RemoteServiceException;
}
