package edu.chd.practice.rmi.contract;

import edu.chd.practice.rmi.contract.dto.InvocationContext;
import edu.chd.practice.rmi.contract.dto.SelectRequest;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface SelectInterface extends Remote {
    String OP_SELECT = "select.select";
    String OP_COUNT = "select.count";

    String[][] select(SelectRequest request, InvocationContext context)
            throws RemoteException, RemoteServiceException;

    long count(SelectRequest request, InvocationContext context)
            throws RemoteException, RemoteServiceException;
}
