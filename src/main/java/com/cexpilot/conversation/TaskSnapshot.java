package com.cexpilot.conversation;

/** 本轮任务候选别名和可恢复版本。别名与服务端对象来自同一份 PlannerInput。 */
public record TaskSnapshot(long queryNo, String turnId, String question, QueryFrame frame,
                           TaskState state, String taskRef, String stateRef) {
    public TaskSnapshot(long queryNo, String turnId, String question, QueryFrame frame) {
        this(queryNo, turnId, question, frame, TaskState.legacy(frame, queryNo, question), turnId, turnId);
    }
    public static TaskSnapshot candidate(TaskState state, String taskRef) {
        return new TaskSnapshot(state.lastQueryNo(), state.turnId(), state.originQuestion(), state.frame(),
                state, taskRef, taskRef + "_v" + state.version());
    }
}
