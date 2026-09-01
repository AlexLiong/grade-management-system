package edu.chd.practice.rmi.contract.dto;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public final class RecoverySnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    private final long sequence;
    private final String eventType;
    private final String aggregateId;
    private final Map<String, String> originalValues;
    private final long createdAtEpochMillis;

    public RecoverySnapshot(long sequence, String eventType, String aggregateId,
                            Map<String, String> originalValues, long createdAtEpochMillis) {
        this.sequence = sequence;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.originalValues = originalValues == null ? Map.of()
                : Collections.unmodifiableMap(new TreeMap<>(originalValues));
        this.createdAtEpochMillis = createdAtEpochMillis;
    }

    public long getSequence() { return sequence; }
    public String getEventType() { return eventType; }
    public String getAggregateId() { return aggregateId; }
    public Map<String, String> getOriginalValues() { return originalValues; }
    public long getCreatedAtEpochMillis() { return createdAtEpochMillis; }
}
