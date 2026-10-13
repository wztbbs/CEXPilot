package com.cexpilot.conversation;

import com.cexpilot.runtime.RequestContext;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Planner 选择的任务与来源版本。NEW 的任务 ID 由服务端提交时分配。 */
public record TaskScope(Action action, TaskState base) {
    public enum Action { NEW, CONTINUE, RESUME }

    public static TaskScope current(TaskState state) {
        return state == null ? new TaskScope(Action.NEW, null) : new TaskScope(Action.CONTINUE, state);
    }

    public QueryFrame frame() { return base == null ? null : base.frame(); }
    public long baseVersion() { return base == null ? 0 : base.version(); }

    /** 回访和延续继承来源时区；节点 TimeSpec 明确指定的时区仍由时间消解器处理。 */
    public RequestContext executionContext(RequestContext request) {
        ZoneId zone = request.userZone() == null ? ZoneOffset.ofHours(8) : request.userZone();
        if (action != Action.NEW && base != null) {
            String inherited = base.timezone() != null ? base.timezone() : frame() == null ? null : frame().timezone();
            if (inherited != null) zone = ZoneId.of(inherited);
        }
        return new RequestContext(zone, request.requestTime());
    }
}
