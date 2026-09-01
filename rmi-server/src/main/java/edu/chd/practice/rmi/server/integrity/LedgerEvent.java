package edu.chd.practice.rmi.server.integrity;

public record LedgerEvent(String eventType, String aggregateType, String aggregateId,
                          String encryptedSnapshot, String actor) {
}
