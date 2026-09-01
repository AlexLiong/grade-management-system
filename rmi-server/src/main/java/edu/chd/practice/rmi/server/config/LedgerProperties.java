package edu.chd.practice.rmi.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ledger")
public class LedgerProperties {
    private String path = "runtime/grade-integrity.ledger";

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
}
