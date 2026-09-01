package edu.chd.practice.rmi.contract.dto;

import java.io.Serializable;

public final class EncryptedGradePayload implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String ciphertext;
    private final String nonce;
    private final String integrity;

    public EncryptedGradePayload(String ciphertext, String nonce, String integrity) {
        this.ciphertext = ciphertext;
        this.nonce = nonce;
        this.integrity = integrity;
    }

    public String getCiphertext() { return ciphertext; }
    public String getNonce() { return nonce; }
    public String getIntegrity() { return integrity; }
}
