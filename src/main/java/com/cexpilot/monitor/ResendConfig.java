package com.cexpilot.monitor;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix="resend")
public class ResendConfig {
    private String apiKey="";
    private String baseUrl="";
    private boolean dryRun;
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun=dryRun; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey=apiKey==null?"":apiKey.trim(); }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl=baseUrl==null?"":baseUrl.trim(); }
    public boolean isConfigured() { return !apiKey.isBlank(); }
    @PostConstruct public void validate() {
        if(apiKey.length()>512 || apiKey.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("RESEND_API_KEY 格式无效");
    }
}
