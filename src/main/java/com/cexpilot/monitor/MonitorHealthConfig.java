package com.cexpilot.monitor;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix="monitor.health")
public class MonitorHealthConfig {
    private int graceSeconds=60,scanStaleSeconds=60,maxViolations=100,maxRules=1000,maxExecutions=200000,queryTimeoutSeconds=3,transactionTimeoutSeconds=20;
    public int getGraceSeconds() { return graceSeconds; } public void setGraceSeconds(int value) { graceSeconds=value; }
    public int getScanStaleSeconds() { return scanStaleSeconds; } public void setScanStaleSeconds(int value) { scanStaleSeconds=value; }
    public int getMaxViolations() { return maxViolations; } public void setMaxViolations(int value) { maxViolations=value; }
    public int getMaxRules() { return maxRules; } public void setMaxRules(int value) { maxRules=value; }
    public int getMaxExecutions() { return maxExecutions; } public void setMaxExecutions(int value) { maxExecutions=value; }
    public int getQueryTimeoutSeconds() { return queryTimeoutSeconds; } public void setQueryTimeoutSeconds(int value) { queryTimeoutSeconds=value; }
    public int getTransactionTimeoutSeconds() { return transactionTimeoutSeconds; } public void setTransactionTimeoutSeconds(int value) { transactionTimeoutSeconds=value; }
    @PostConstruct public void validate() {
        if(graceSeconds<1 || scanStaleSeconds<1 || maxViolations<1 || maxViolations>1000 || maxRules<1 || maxRules>10000
                || maxExecutions<1 || maxExecutions>1000000 || queryTimeoutSeconds<1 || queryTimeoutSeconds>30
                || transactionTimeoutSeconds<queryTimeoutSeconds || transactionTimeoutSeconds>60)
            throw new IllegalArgumentException("monitor.health 配置超出范围");
    }
}
