package edu.campus.common;

public class ApiException extends RuntimeException {
    public final int status;
    public final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static void require(boolean ok, int status, String message) {
        if (!ok) {
            throw new ApiException(status, "REQUEST_REJECTED", message);
        }
    }
}
