package edu.chd.practice.rmi.contract;

import edu.chd.practice.rmi.contract.dto.InvocationContext;
import edu.chd.practice.rmi.contract.dto.IdempotencyProbe;
import edu.chd.practice.rmi.contract.dto.MutationCommand;
import edu.chd.practice.rmi.contract.dto.TransactionRequest;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface ManipulationInterface extends Remote {
    String OP_EXECUTE = "manipulation.execute";
    String OP_TRANSACTION = "manipulation.transaction";
    String OP_IDEMPOTENCY_PROBE = "manipulation.transaction.idempotency-probe";

    boolean execute(MutationCommand command, InvocationContext context)
            throws RemoteException, RemoteServiceException;

    boolean executeTransaction(TransactionRequest request, InvocationContext context)
            throws RemoteException, RemoteServiceException;

    boolean transactionCompleted(IdempotencyProbe probe, InvocationContext context)
            throws RemoteException, RemoteServiceException;
}
