package edu.chd.practice.rmi.contract.dto;

import java.io.Serializable;

public final class HealthStatus implements Serializable {
    private static final long serialVersionUID = 1L;

    private final boolean healthy;
    private final String database;
    private final String serviceVersion;
    private final long serverTimeEpochMillis;

    public HealthStatus(boolean healthy, String database, String serviceVersion,
                        long serverTimeEpochMillis) {
        this.healthy = healthy;
        this.database = database;
        this.serviceVersion = serviceVersion;
        this.serverTimeEpochMillis = serverTimeEpochMillis;
    }

    public boolean isHealthy() { return healthy; }
    public String getDatabase() { return database; }
    public String getServiceVersion() { return serviceVersion; }
    public long getServerTimeEpochMillis() { return serverTimeEpochMillis; }
}
