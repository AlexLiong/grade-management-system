package edu.chd.practice.rmi.contract;

import edu.chd.practice.rmi.contract.dto.HealthStatus;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface HealthInterface extends Remote {
    HealthStatus check() throws RemoteException;
}
