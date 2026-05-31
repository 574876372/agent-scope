package com.cl.agent.biz.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.cl.agent.biz.IAgentBiz;
import com.cl.agent.biz.ISqlAgentBiz;
import com.cl.agent.commons.UserContext;
import com.cl.agent.dto.ChatRequest;
import com.cl.agent.dto.ChatStreamEvent;
import com.cl.agent.dto.SendMessageRequest;
import com.cl.agent.exception.BizException;
import com.cl.agent.model.ChatMessage;
import com.cl.agent.model.Conversation;
import com.cl.agent.service.IChatService;
import com.cl.agent.sql.executor.SqlConfirmExecutor;
import com.cl.agent.sql.executor.SqlExecutionResult;
import com.cl.agent.stream.StreamAccumulator;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * {@link ISqlAgentBiz} 默认实现。
 *
 * <h2>SSE 帧顺序</h2>
 * <ol>
 *   <li>{@code [CONV_ID]<id>} 控制帧</li>
 *   <li>{@code tool_result} —— 包含 {@link SqlExecutionResult} 的 JSON</li>
 *   <li>EXECUTED 分支：若关联 Agent，再由 LLM 流式产出 {@code reasoning} / {@code message} 帧；
 *       否则直接推一帧简单 {@code message} 摘要</li>
 *   <li>REJECTED / TOKEN_EXPIRED / ERROR 分支：仅推一帧 {@code message}</li>
 *   <li>持久化助手消息</li>
 *   <li>{@code [DONE]} 控制帧 + Flux 关闭</li>
 * </ol>
 *
 * <h2>持久化策略</h2>
 * 整个 HITL 确认作为一条独立的 assistant 消息落库；内容采用与普通流相同的
 * {@code <think>…</think> + Action/Observation + message} 拼接格式，
 * 便于前端历史回放沿用现有解析逻辑。
 */
@Slf4j
@Service
public class SqlAgentBizImpl implements ISqlAgentBiz {

    /** 给 LLM 的二次总结提示中，最多附带的结果行数，避免 token 爆炸 */
    private static final int SUMMARY_ROW_LIMIT = 20;

    /** 工具结果帧中虚拟的 tool 名 */
    private static final String TOOL_NAME = "query_database";

    /** starter 提供的执行器 */
    @Autowired
    private SqlConfirmExecutor sqlConfirmExecutor;

    /** 会话持久化服务 */
    @Autowired
    private IChatService chatService;

    /** Agent 业务接口，用于二次总结 */
    @Autowired
    private IAgentBiz agentBiz;

    /**
     * {@inheritDoc}
     */
    @Override
    public Flux<ChatStreamEvent> confirmSqlExecution(SendMessageRequest request) {
        return Flux.create(sink -> {
            try {
                doConfirm(request, sink);
            } catch (BizException be) {
                sink.next(new ChatStreamEvent("error", be.getMessage()));
                sink.complete();
            } catch (Exception e) {
                log.error("[SqlConfirm] 流式确认异常", e);
                sink.next(new ChatStreamEvent("error",
                        e.getMessage() == null ? "SQL 确认执行异常" : e.getMessage()));
                sink.complete();
            }
        });
    }

    /**
     * SSE 主体流程；任何抛出的异常会被 {@link #confirmSqlExecution} 兜底转为 error 帧。
     *
     * @param request 复用的请求体
     * @param sink    Flux 发射器
     */
    private void doConfirm(SendMessageRequest request, FluxSink<ChatStreamEvent> sink) {
        String convId = request.getConversationId();
        if (convId == null || convId.isBlank()) {
            throw new BizException(400, "HITL 确认必须传入 conversationId");
        }
        String userId = UserContext.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new BizException(401, "缺少用户上下文");
        }

        sink.next(new ChatStreamEvent(null, "[CONV_ID]" + convId));

        Conversation conv = chatService.getById(convId);
        if (conv == null) {
            throw new BizException(404, "会话不存在: " + convId);
        }

        SqlExecutionResult result = sqlConfirmExecutor.execute(
                request.getConfirmToken(), request.getSqlAction(), request.getEditedSql(), userId);

        String toolJson = buildToolResultJson(result);
        sink.next(new ChatStreamEvent("tool_result", toolJson));

        StreamAccumulator accumulator = new StreamAccumulator();
        accumulator.appendToolResultJson(toolJson);

        boolean executed = "EXECUTED".equals(result.getStatus());
        boolean canSummarize = executed && conv.getAgentId() != null
                && result.getRowCount() > 0;

        if (!canSummarize) {
            String prompt = buildSimpleMessage(result);
            sink.next(new ChatStreamEvent("message", prompt));
            accumulator.appendMessage(prompt);
            persistAssistantMessage(conv, accumulator.buildPersistContent());
            sink.next(new ChatStreamEvent(null, "[DONE]"));
            sink.complete();
            return;
        }

        String originalQuestion = findLastUserMessage(conv);
        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setContent(buildSummaryPrompt(originalQuestion, result));
        chatRequest.setHistory(Collections.emptyList());

        agentBiz.chatStream(conv.getAgentId(), chatRequest).subscribe(
                event -> {
                    ChatStreamEvent sse = mapAgentEventToSse(event, accumulator);
                    if (sse != null) {
                        sink.next(sse);
                    }
                },
                error -> {
                    log.error("[SqlConfirm] LLM 二次总结失败 convId={}: {}", convId, error.getMessage(), error);
                    String msg = "查询已完成，但生成总结时出错：" + error.getMessage();
                    sink.next(new ChatStreamEvent("message", msg));
                    accumulator.appendMessage(msg);
                    persistAssistantMessage(conv, accumulator.buildPersistContent());
                    sink.next(new ChatStreamEvent(null, "[DONE]"));
                    sink.complete();
                },
                () -> {
                    persistAssistantMessage(conv, accumulator.buildPersistContent());
                    sink.next(new ChatStreamEvent(null, "[DONE]"));
                    sink.complete();
                }
        );
    }

    /**
     * 把 {@link SqlExecutionResult} 包装为前端期望的 {@code {tool, output}} JSON 帧。
     *
     * <p>output 字段内嵌的是另一段 JSON（执行结果），前端按需二次 JSON.parse。</p>
     *
     * @param result 执行结果
     * @return tool_result 帧载荷
     */
    private String buildToolResultJson(SqlExecutionResult result) {
        String innerJson = JSON.toJSONString(result);
        JSONObject outer = new JSONObject();
        outer.put("tool", TOOL_NAME);
        outer.put("output", innerJson);
        return outer.toString();
    }

    /**
     * 非 EXECUTED 分支或无可总结数据时的纯文本提示。
     *
     * @param result 执行结果
     * @return 用户可见的提示
     */
    private String buildSimpleMessage(SqlExecutionResult result) {
        switch (result.getStatus() == null ? "" : result.getStatus()) {
            case "REJECTED":
                return result.getMessage() == null ? "用户已取消 SQL 执行" : result.getMessage();
            case "TOKEN_EXPIRED":
                return "审批 token 已过期或不存在，请重新发起查询";
            case "ERROR":
                return "SQL 执行失败：" + (result.getError() == null ? "未知错误" : result.getError());
            case "EXECUTED":
                if (result.getRowCount() == 0) {
                    return "SQL 已执行，返回 0 行数据。";
                }
                return "SQL 已执行，返回 " + result.getRowCount() + " 行数据，耗时 "
                        + result.getElapsedMs() + "ms。";
            default:
                return "SQL 操作已完成。";
        }
    }

    /**
     * 构造给 LLM 的二次总结提示词。
     *
     * <p>策略：只把前 {@value #SUMMARY_ROW_LIMIT} 行喂给 LLM，避免大表 token 爆炸；
     * 同时显式声明任务为"用自然语言概括"，禁止 LLM 重复 SQL 本身。</p>
     *
     * @param question 用户原始问题
     * @param result   执行结果
     * @return prompt 文本
     */
    private String buildSummaryPrompt(String question, SqlExecutionResult result) {
        int sampleSize = Math.min(SUMMARY_ROW_LIMIT, result.getRows().size());
        StringBuilder rowsBlock = new StringBuilder();
        rowsBlock.append(String.join(" | ", result.getColumns())).append('\n');
        for (int i = 0; i < sampleSize; i++) {
            List<Object> row = result.getRows().get(i);
            for (int j = 0; j < row.size(); j++) {
                if (j > 0) {
                    rowsBlock.append(" | ");
                }
                rowsBlock.append(row.get(j) == null ? "" : row.get(j).toString());
            }
            rowsBlock.append('\n');
        }

        StringBuilder sb = new StringBuilder();
        sb.append("用户原始问题：").append(question == null || question.isBlank() ? "(无)" : question).append("\n\n");
        sb.append("以下是执行 SQL 返回的结果（共 ").append(result.getRowCount()).append(" 行");
        if (result.getRowCount() > sampleSize) {
            sb.append("，仅展示前 ").append(sampleSize).append(" 行");
        }
        sb.append("）：\n").append(rowsBlock).append('\n');
        sb.append("请根据上述数据，用一段简洁自然的中文直接回答用户的问题；")
                .append("禁止重复 SQL 本身，禁止逐行复述，重点提炼数据洞察。");
        return sb.toString();
    }

    /**
     * 从会话历史中倒序找最后一条用户消息。
     *
     * @param conv 会话实体
     * @return 最近一条用户消息内容，未找到返回空字符串
     */
    private String findLastUserMessage(Conversation conv) {
        if (conv == null || conv.getMessages() == null) {
            return "";
        }
        for (int i = conv.getMessages().size() - 1; i >= 0; i--) {
            ChatMessage m = conv.getMessages().get(i);
            if ("user".equals(m.getRole()) && m.getContent() != null) {
                return m.getContent();
            }
        }
        return "";
    }

    /**
     * 持久化助手消息到会话。
     *
     * @param conv    会话实体
     * @param content 拼装好的完整文本
     */
    private void persistAssistantMessage(Conversation conv, String content) {
        if (conv == null) {
            return;
        }
        ChatMessage aiMsg = new ChatMessage();
        aiMsg.setRole("assistant");
        aiMsg.setContent(content == null ? "" : content);
        aiMsg.setTimestamp(LocalDateTime.now());
        conv.getMessages().add(aiMsg);
        conv.setUpdateTime(LocalDateTime.now());
        chatService.save(conv);
    }

    /**
     * 把 Agent 事件映射为 SSE 帧，与 {@code ChatBizImpl.mapAgentEventToSse} 行为一致。
     *
     * <p>当 Agent 在二次总结过程中又产生 TOOL_RESULT（极少见，但模型可能再调一次工具），
     * 直接透传，不做特殊处理。</p>
     *
     * @param event       AgentScope 事件
     * @param accumulator 累积器
     * @return SSE 帧；不应推送时返回 null
     */
    private ChatStreamEvent mapAgentEventToSse(Event event, StreamAccumulator accumulator) {
        EventType type = event.getType();
        String text = extractEventText(event);
        if (type == EventType.REASONING) {
            accumulator.appendReasoning(text);
            return new ChatStreamEvent("reasoning", text);
        }
        if (type == EventType.TOOL_RESULT) {
            // 总结阶段一般不会触发工具调用，但若发生仍透传
            return new ChatStreamEvent("tool_result", text);
        }
        if (type == EventType.AGENT_RESULT) {
            accumulator.appendMessage(text);
            return new ChatStreamEvent("message", text);
        }
        accumulator.appendMessage(text);
        return new ChatStreamEvent("message", text);
    }

    /**
     * 从事件中取出文本内容（与 ChatBizImpl 同名工具方法等价）。
     *
     * @param event 事件
     * @return 文本，无内容时返回空字符串
     */
    private String extractEventText(Event event) {
        Msg msg = event.getMessage();
        if (msg == null) {
            return "";
        }
        String text = msg.getTextContent();
        return text == null ? "" : text;
    }
}
