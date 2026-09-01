package edu.chd.practice.rmi.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security")
public class SecurityProperties {
    private long requestMaxAgeSeconds = 300;
    private int maxPageSize = 500;
    private int maxTransactionCommands = 200;
    private String gradeKey = "";
    private String gradeKeyFile = "runtime/grade-data.key";

    public long getRequestMaxAgeSeconds() { return requestMaxAgeSeconds; }
    public void setRequestMaxAgeSeconds(long requestMaxAgeSeconds) { this.requestMaxAgeSeconds = requestMaxAgeSeconds; }
    public int getMaxPageSize() { return maxPageSize; }
    public void setMaxPageSize(int maxPageSize) { this.maxPageSize = maxPageSize; }
    public int getMaxTransactionCommands() { return maxTransactionCommands; }
    public void setMaxTransactionCommands(int maxTransactionCommands) { this.maxTransactionCommands = maxTransactionCommands; }
    public String getGradeKey() { return gradeKey; }
    public void setGradeKey(String gradeKey) { this.gradeKey = gradeKey; }
    public String getGradeKeyFile() { return gradeKeyFile; }
    public void setGradeKeyFile(String gradeKeyFile) { this.gradeKeyFile = gradeKeyFile; }
}
