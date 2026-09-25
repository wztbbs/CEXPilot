package com.cexpilot.config;

import com.cexpilot.time.TimeRangeResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时间基础设施：requestTime 统一由程序注入的 Clock 提供，LLM 不参与当前时间的确定。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean
    public TimeRangeResolver timeRangeResolver(Clock clock) {
        return new TimeRangeResolver(clock);
    }
}
