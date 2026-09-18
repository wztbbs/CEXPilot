package com.cexpilot.api;

import com.cexpilot.config.LlmConfig;
import com.cexpilot.conversation.ConversationService;
import com.cexpilot.dag.DagRuntime;
import com.cexpilot.intent.IntentRegistry;
import com.cexpilot.intent.UnmatchedQueryRepository;
import com.cexpilot.runtime.ExecutionResult;
import com.cexpilot.trace.DbTraceSink;
import com.cexpilot.trace.TraceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 一次问答的编排：对话上下文 → 建 trace → DagRuntime（一次 LLM 调用完成领域判断 +
 * intent 归类 + 查询规划，随后执行 DAG 并生成回答）→ 落 trace / 记 Query。
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    private final ConversationService conversation;
    private final DagRuntime runtime;
    private final UnmatchedQueryRepository unmatchedQueryRepository;
    private final TraceRepository traceRepository;
    private final DbTraceSink traceSink;
    private final LlmConfig llmConfig;

    public AskService(ConversationService conversation,
                      DagRuntime runtime,
                      UnmatchedQueryRepository unmatchedQueryRepository,
                      TraceRepository traceRepository,
                      DbTraceSink traceSink,
                      LlmConfig llmConfig) {
        this.conversation = conversation;
        this.runtime = runtime;
        this.unmatchedQueryRepository = unmatchedQueryRepository;
        this.traceRepository = traceRepository;
        this.traceSink = traceSink;
        this.llmConfig = llmConfig;
    }

    /**
     * 一次问答的完整编排。这里只做流程串联：领域判断、查询规划与 DAG 执行在 DagRuntime
     * 里，计算逻辑在各工具内部。
     */
    public AskResponse ask(String conversationId, String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }

        // 1. 落定对话：conversationId 为空则新建对话；不为空则沿用（多轮追问的载体）。
        //    对话只保留最近几个 Query，不做长期记忆。
        String resolvedConversationId = conversation.getOrCreateConversation(conversationId, question);

        // 2. 渲染对话上下文：把最近几个 Q&A 拼成文本，注入 system prompt，
        //    让 LLM 能解析"那 OKX 呢"这类指代。
        String conversationContext = conversation.renderContext(resolvedConversationId);

        // 3. 开启 trace：写入一条 RUNNING 记录。traceId 是本次问答的全局标识——
        //    后续每次 LLM 调用、每次工具调用都会以它为外键落 trace_event，
        //    用户点 👎 后凭它完整回放"当时调了什么工具、拿到了什么数据"。
        //    同时记录 model 和 promptVersion（prompt 内容的哈希），便于排查答案可复现性。
        String traceId = UUID.randomUUID().toString();
        traceRepository.startTrace(traceId, resolvedConversationId, question,
                llmConfig.getNormal().getModel(), runtime.promptVersion());

        long start = System.currentTimeMillis();
        try {
            // 4. 执行 DAG（合并规划 → 并行执行 → 生成回答），成功后收尾：trace 置为 SUCCESS，
            //    记录答案、intent 归类（统计 hint）、层数、工具调用数、token 用量与成本；
            //    本次 Query 写入 conversation_query，成为后续追问的上下文。
            //    intent 归类为 UNKNOWN 的 query 落 unmatched_query，作为能力缺口数据集。
            ExecutionResult result = runtime.execute(question, conversationContext, traceId, traceSink);
            long durationMs = System.currentTimeMillis() - start;
            double cost = computeCost(result.promptTokens(), result.completionTokens());

            if (IntentRegistry.UNKNOWN.equals(result.intent())) {
                unmatchedQueryRepository.save(traceId, resolvedConversationId, question,
                        "{\"intent\":\"UNKNOWN\"}");
            }
            traceRepository.finishTrace(traceId, "SUCCESS", result.answer(), result.steps(),
                    result.toolCallCount(), result.promptTokens(), result.completionTokens(),
                    cost, durationMs, null, result.intent());
            conversation.recordQuery(resolvedConversationId, question, result.answer(), traceId);

            // 5. evidence（工具产出的事实集）随答案一起返回，前端可展示"答案引用了哪些数据"。
            return new AskResponse(resolvedConversationId, traceId, result.answer(), result.evidence(),
                    result.toolCallCount(), result.steps(), durationMs);
        } catch (Exception e) {
            // 失败同样要落 trace：失败的现场数据正是后续定位问题、固化评测 case 的原料。
            long durationMs = System.currentTimeMillis() - start;
            log.warn("问答失败 traceId={}: {}", traceId, e.getMessage());
            traceRepository.finishTrace(traceId, "FAILED", null, 0, 0,
                    null, null, null, durationMs, e.getMessage(), null);
            throw e;
        }
    }

    /** 按普通模型的每千 token 单价折算成本；单价为 0 时成本为 0，不影响主流程。 */
    private double computeCost(int promptTokens, int completionTokens) {
        return promptTokens / 1000.0 * llmConfig.getNormal().getPriceInputPer1k()
                + completionTokens / 1000.0 * llmConfig.getNormal().getPriceOutputPer1k();
    }
}
