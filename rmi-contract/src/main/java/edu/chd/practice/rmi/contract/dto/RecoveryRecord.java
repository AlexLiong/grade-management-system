package edu.chd.practice.rmi.contract.dto;

import java.io.Serializable;

public final class RecoveryRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    private final long sequence;
    private final String eventType;
    private final String aggregateType;
    private final String aggregateId;
    private final String encryptedSnapshot;
    private final String previousHash;
    private final String entryHash;
    private final long createdAtEpochMillis;
    private final String actor;

    public RecoveryRecord(long sequence, String eventType, String aggregateType, String aggregateId,
                          String encryptedSnapshot, String previousHash, String entryHash,
                          long createdAtEpochMillis, String actor) {
        this.sequence = sequence;
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.encryptedSnapshot = encryptedSnapshot;
        this.previousHash = previousHash;
        this.entryHash = entryHash;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.actor = actor;
    }

    public long getSequence() { return sequence; }
    public String getEventType() { return eventType; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEncryptedSnapshot() { return encryptedSnapshot; }
    public String getPreviousHash() { return previousHash; }
    public String getEntryHash() { return entryHash; }
    public long getCreatedAtEpochMillis() { return createdAtEpochMillis; }
    public String getActor() { return actor; }
}
