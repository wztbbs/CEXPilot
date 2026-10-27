package com.cexpilot.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.util.function.Consumer;
import static com.cexpilot.monitor.MonitorModels.*;

/** 仅执行已确认的Monitor Plan；检查点由管理器提供的回调持久化。 */
@Component
public class MonitorWorker {
    private final MonitorPlanRunner runner;
    public MonitorWorker(MonitorPlanRunner runner) { this.runner=runner; }
    public Check run(MonitorWork work,Consumer<JsonNode> save) {
        if(work.version().definition().path("schemaVersion").asInt()!=2)
            throw new MonitorWorkFailure("INVALID_DEFINITION","不支持的监控定义，请通过聊天重新创建",false);
        return runner.run(work,save);
    }
}
