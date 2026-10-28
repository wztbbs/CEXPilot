package com.cexpilot.monitor;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 监控调度与发件配置；收件人由用户确认并保存在任务上，访问凭证仅用于巡检。 */
@Configuration
@ConfigurationProperties(prefix = "monitor")
public class MonitorConfig {
    private boolean enabled;
    private String accessToken = "";
    private String mailFrom = "";
    private int publicationDelaySeconds = 20;
    private int deliveryLeaseSeconds = 120;
    private int maxDeliveryAttempts = 3;
    private int maxExecutionAttempts = 3;
    private int workTimeoutSeconds = 60;
    @PostConstruct
    public void validate() {
        if (!enabled) return;
        if (!accessToken.isEmpty() && accessToken.length() < 32) throw new IllegalArgumentException("MONITOR_ACCESS_TOKEN 如配置则至少 32 字符");
        if (!MonitorEmailAddress.valid(mailFrom)) {
            throw new IllegalArgumentException("监控需配置有效 MONITOR_MAIL_FROM");
        }
        if (publicationDelaySeconds < 1 || publicationDelaySeconds > 120 || deliveryLeaseSeconds < 60
                || maxDeliveryAttempts < 1 || maxDeliveryAttempts > 10
                || maxExecutionAttempts < 1 || maxExecutionAttempts > 10 || workTimeoutSeconds < 1 || workTimeoutSeconds > 300) throw new IllegalArgumentException("monitor 时间/重试配置无效");
    }

    public int getMaxExecutionAttempts() { return maxExecutionAttempts; }
    public void setMaxExecutionAttempts(int value) { maxExecutionAttempts=value; }
    public int getWorkTimeoutSeconds() { return workTimeoutSeconds; }
    public void setWorkTimeoutSeconds(int value) { workTimeoutSeconds=value; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String value) { accessToken = value; }
    public String getMailFrom() { return mailFrom; }
    public void setMailFrom(String value) { mailFrom = value; }
    public int getPublicationDelaySeconds() { return publicationDelaySeconds; }
    public void setPublicationDelaySeconds(int value) { publicationDelaySeconds = value; }
    public int getDeliveryLeaseSeconds() { return deliveryLeaseSeconds; }
    public void setDeliveryLeaseSeconds(int value) { deliveryLeaseSeconds = value; }
    public int getMaxDeliveryAttempts() { return maxDeliveryAttempts; }
    public void setMaxDeliveryAttempts(int value) { maxDeliveryAttempts = value; }
}
