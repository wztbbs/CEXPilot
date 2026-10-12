package com.cexpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 会话与轮次配置（多轮追问阶段一 a）。
 */
@Configuration
@ConfigurationProperties(prefix = "conversation")
public class ConversationConfig {

    /**
     * 同会话串行执行占用的租约时长。执行期间不续租（首期从简）：超过租约仍未提交，
     * 允许新请求接管执行占用；被接管的旧执行者提交时 fencing_token 校验失败，不会覆盖新状态。
     */
    private Duration turnLease = Duration.ofMinutes(10);

    public Duration getTurnLease() {
        return turnLease;
    }

    public void setTurnLease(Duration turnLease) {
        this.turnLease = turnLease;
    }
}
