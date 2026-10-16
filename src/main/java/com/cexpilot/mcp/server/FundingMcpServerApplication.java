package com.cexpilot.mcp.server;

import com.cexpilot.config.ExchangeConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/**
 * 行情 MCP Server：独立进程（默认 :8090），把币安永续行情以标准 MCP 协议
 * （Streamable HTTP，POST /mcp）对外提供。只装配交易所客户端层（ExchangeConfig
 * + BinanceClient），不依赖主应用的 planner/dag/DB——数据流向只能是
 * Client(主应用) → Server(本进程) → 交易所代理，物理上不存在回环。
 *
 * <p>@Profile 注解同时防止主应用的组件扫描把本类当配置类加载。
 *
 * <p>本地启动：mvn spring-boot:run -Dspring-boot.run.main-class=com.cexpilot.mcp.server.FundingMcpServerApplication
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, SqlInitializationAutoConfiguration.class})
@Import(ExchangeConfig.class)
@Profile("funding-mcp")
public class FundingMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(FundingMcpServerApplication.class);
        app.setAdditionalProfiles("funding-mcp");
        app.run(args);
    }
}
