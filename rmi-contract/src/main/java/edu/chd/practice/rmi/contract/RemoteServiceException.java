package edu.chd.practice.rmi.contract;

public class RemoteServiceException extends Exception {
    private static final long serialVersionUID = 1L;

    private final String code;

    public RemoteServiceException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
