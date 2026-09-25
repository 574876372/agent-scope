package com.cl.agent.biz.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.cl.agent.biz.IAgentBiz;
import com.cl.agent.biz.IGenericHitlBiz;
import com.cl.agent.commons.UserContext;
import com.cl.agent.dto.ChatRequest;
import com.cl.agent.dto.ChatStreamEvent;
import com.cl.agent.dto.SendMessageRequest;
import com.cl.agent.exception.BizException;
import com.cl.agent.model.ChatMessage;
import com.cl.agent.model.Conversation;
import com.cl.agent.service.IChatService;
import com.cl.agent.stream.StreamAccumulator;
import com.cl.agent.tool.core.AgentToolRegistry;
import com.cl.agent.tool.core.ReflectiveAgentTool;
import com.cl.agent.hitl.core.GenericApprovalTokenStore;
import com.cl.agent.hitl.core.ApprovalContext;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 通用人机协同（HITL）审批执行的业务逻辑编排层实现类。
 * <p>使用说明：继承自 {@link IGenericHitlBiz}。在用户对高危工具执行确认、编辑或取消后，
 * 该服务负责从 TokenStore 取回上下文，合并最新参数，直接反射调用真实工具，
 * 并将运行数据回流给绑定的智能体进行二次大模型总结，实现流式 SSE 帧的推送。</p>
 */
@Service
@Slf4j
public class GenericHitlBizImpl implements IGenericHitlBiz {

    /** 二次回复总结大模型最多附带的返回值长度，防止超长 token 耗费 */
    private static final int MAX_SUMMARY_CHARS = 3000;

    /** 通用审批 Token 管理器 */
    @Autowired
    private GenericApprovalTokenStore genericApprovalTokenStore;

    /** 全局反射工具注册表，用于获取真实的 ReflectiveAgentTool 执行 */
    @Autowired
    private AgentToolRegistry agentToolRegistry;

    /** 会话历史数据处理服务 */
    @Autowired
    private IChatService chatService;

    /** Agent 业务模块，用于调度大模型流式对话进行二次总结 */
    @Autowired
    private IAgentBiz agentBiz;

    /**
     * {@inheritDoc}
     */
    @Override
    public Flux<ChatStreamEvent> confirmExecution(SendMessageRequest request) {
        return Flux.create(sink -> {
            try {
                doConfirm(request, sink);
            } catch (BizException be) {
                log.warn("[HITL-Biz] 审批流程处理失败: {}", be.getMessage());
                sink.next(new ChatStreamEvent("error", be.getMessage()));
                sink.complete();
            } catch (Exception e) {
                log.error("[HITL-Biz] 审批流程系统异常", e);
                sink.next(new ChatStreamEvent("error", e.getMessage() == null ? "审批执行异常" : e.getMessage()));
                sink.complete();
            }
        });
    }

    /**
     * 执行通用审批响应逻辑的核心子步骤。
     * <p>使用说明：由 {@link #confirmExecution} 内部包围 try-catch 触发；
     * 校验会话和用户上下文，提取 Token 信息，分类处理 REJECT、APPROVE 和 EDIT 动作并推送对应的流数据帧。</p>
     *
     * @param request 请求参数对象，非空
     * @param sink    Flux 事件推送发射器，非空
     */
    private void doConfirm(SendMessageRequest request, FluxSink<ChatStreamEvent> sink) {
        String convId = request.getConversationId();
        if (convId == null || convId.isBlank()) {
            throw new BizException(400, "会话 ID 不能为空");
        }
        String token = request.getHitlToken();
        if (token == null || token.isBlank()) {
            throw new BizException(400, "审批 Token 不能为空");
        }
        String action = request.getHitlAction();
        if (action == null || action.isBlank()) {
            throw new BizException(400, "审批 Action 不能为空");
        }
        String currentUserId = UserContext.getUserId();
        if (currentUserId == null || currentUserId.isBlank()) {
            throw new BizException(401, "缺少用户上下文，拒绝审批");
        }

        // 推送会话 ID 控制帧，便于前端关联
        sink.next(new ChatStreamEvent(null, "[CONV_ID]" + convId));

        Conversation conv = chatService.getById(convId);
        if (conv == null) {
            throw new BizException(404, "对话会话不存在: " + convId);
        }

        // 消费 Token
        java.util.Optional<ApprovalContext> ctxOpt = genericApprovalTokenStore.take(token);
        if (ctxOpt.isEmpty()) {
            sink.next(new ChatStreamEvent("message", "审批 Token 不存在或已过期，请重新发起。"));
            sink.next(new ChatStreamEvent(null, "[DONE]"));
            sink.complete();
            return;
        }

        ApprovalContext ctx = ctxOpt.get();
        if (!currentUserId.equals(ctx.getUserId())) {
            log.warn("[HITL-Biz] Token 安全校验失败，存在跨用户重放尝试: tokenUser={}, currentUser={}", ctx.getUserId(), currentUserId);
            throw new BizException(403, "无权使用该审批 Token");
        }

        // 分支处理：拒绝执行
        if ("REJECT".equalsIgnoreCase(action)) {
            String rejectMsg = String.format("用户已拒绝工具 [%s] 的执行请求。", ctx.getToolName());
            sink.next(new ChatStreamEvent("message", rejectMsg));
            persistAssistantMessage(conv, rejectMsg);
            sink.next(new ChatStreamEvent(null, "[DONE]"));
            sink.complete();
            log.info("[HITL-Biz] 用户拒绝了工具执行: token={}, toolName={}", token, ctx.getToolName());
            return;
        }

        // 确认或编辑执行：合并参数
        Map<String, Object> finalParams = ctx.getParameters();
        if ("EDIT".equalsIgnoreCase(action)) {
            if (request.getEditedParameters() != null) {
                finalParams = request.getEditedParameters();
                log.info("[HITL-Biz] 审批编辑执行，使用更新后的参数: {}", finalParams);
            }
        }

        ReflectiveAgentTool tool = agentToolRegistry.getTool(ctx.getToolName());
        if (tool == null) {
            throw new BizException(404, "工具不存在: " + ctx.getToolName());
        }

        log.info("[HITL-Biz] 开始绕过拦截器，直接反射调用真实工具: name={}", tool.getName());
        long startMs = System.currentTimeMillis();

        // 异步执行真实方法
        Map<String, Object> invokeParams = finalParams;
        tool.executeDirectly(invokeParams)
                .contextWrite(context -> context.put("userId", currentUserId))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        result -> handleExecutionSuccess(result, tool.getName(), conv, startMs, sink, currentUserId),
                        error -> handleExecutionError(error, tool.getName(), conv, startMs, sink)
                );
    }

    /**
     * 工具成功执行后的回调推送及二次总结处理。
     * <p>使用说明：由 {@code doConfirm} 内部异步订阅成功返回时调用；
     * 推送 tool_result 给前端，并判断是否关联 Agent 唤醒流式大模型进行智能总结回答。</p>
     *
     * @param result   工具运行封装结果，非空
     * @param toolName 工具名称，非空
     * @param conv     会话实体，非空
     * @param startMs  启动时间戳
     * @param sink     事件发射器，非空
     */
    private void handleExecutionSuccess(ToolResultBlock result, String toolName, Conversation conv,
                                        long startMs, FluxSink<ChatStreamEvent> sink, String userId) {
        long elapsed = System.currentTimeMillis() - startMs;
        log.info("[HITL-Biz] 工具 {} 真实执行成功, 耗时 {}ms", toolName, elapsed);

        // 获取工具返回值文本
        StringBuilder sb = new StringBuilder();
        if (result.getOutput() != null) {
            for (Object obj : result.getOutput()) {
                if (obj != null) sb.append(obj.toString());
            }
        }
        String outputStr = sb.toString();

        // 1. 构建工具返回帧并推送给前端
        String toolJson = buildToolResultJson(toolName, outputStr);
        sink.next(new ChatStreamEvent("tool_result", toolJson));

        StreamAccumulator accumulator = new StreamAccumulator();
        accumulator.appendToolResultJson(toolJson);

        // 2. 检查是否可进行大模型二次总结
        boolean canSummarize = conv.getAgentId() != null && !outputStr.isBlank();
        if (!canSummarize) {
            String directMsg = buildSimpleMessage(toolName, outputStr, elapsed);
            sink.next(new ChatStreamEvent("message", directMsg));
            accumulator.appendMessage(directMsg);
            persistAssistantMessage(conv, accumulator.buildPersistContent());
            sink.next(new ChatStreamEvent(null, "[DONE]"));
            sink.complete();
            return;
        }

        // 3. 构建二次总结提示词并调用 Agent 续写
        String originalQuestion = findLastUserMessage(conv);
        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setContent(buildSummaryPrompt(originalQuestion, toolName, outputStr));
        chatRequest.setHistory(Collections.emptyList());

        agentBiz.chatStream(conv.getAgentId(), chatRequest)
                .contextWrite(context -> context.put("userId", userId))
                .subscribe(
                event -> {
                    ChatStreamEvent sse = mapAgentEventToSse(event, accumulator);
                    if (sse != null) {
                        sink.next(sse);
                    }
                },
                error -> {
                    log.error("[HITL-Biz] LLM 总结失败: {}", error.getMessage(), error);
                    String errMsg = "工具执行成功，但生成总结时失败: " + error.getMessage();
                    sink.next(new ChatStreamEvent("message", errMsg));
                    accumulator.appendMessage(errMsg);
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
     * 工具执行抛出异常时的降级推送处理。
     * <p>使用说明：由 {@code doConfirm} 内部异步订阅出错时触发；将异常格式化推送为错误信息并完成流。</p>
     *
     * @param error    异常对象，非空
     * @param toolName 工具名，非空
     * @param conv     会话实体，非空
     * @param startMs  启动时间
     * @param sink     发射器，非空
     */
    private void handleExecutionError(Throwable error, String toolName, Conversation conv,
                                      long startMs, FluxSink<ChatStreamEvent> sink) {
        long elapsed = System.currentTimeMillis() - startMs;
        log.error("[HITL-Biz] 工具 {} 执行失败, 耗时 {}ms: {}", toolName, elapsed, error.getMessage(), error);
        String errMsg = String.format("工具 [%s] 真实执行失败: %s", toolName, error.getMessage());
        sink.next(new ChatStreamEvent("error", errMsg));
        sink.next(new ChatStreamEvent(null, "[DONE]"));
        sink.complete();
    }

    /**
     * 将工具执行名称和返回结果序列化为标准的工具执行结果 DTO JSON。
     *
     * @param toolName 工具名
     * @param output   输出文本
     * @return 序列化后的 JSON 字符串
     */
    private String buildToolResultJson(String toolName, String output) {
        JSONObject outer = new JSONObject();
        outer.put("tool", toolName);
        outer.put("output", output);
        return outer.toString();
    }

    /**
     * 无法大模型总结时，为前端渲染简易的工具结果文案。
     *
     * @param toolName  工具名
     * @param output    输出内容
     * @param elapsedMs 耗时
     * @return 格式化后的通知描述
     */
    private String buildSimpleMessage(String toolName, String output, long elapsedMs) {
        if (output == null || output.isBlank()) {
            return String.format("工具 [%s] 已运行成功，未返回任何数据（耗时 %dms）。", toolName, elapsedMs);
        }
        return String.format("工具 [%s] 已运行成功，返回结果：%s（耗时 %dms）。", toolName, output, elapsedMs);
    }

    /**
     * 生成提供给大模型总结最终答案的提示词 Prompt。
     *
     * @param question 原始问题
     * @param toolName 工具名称
     * @param output   工具真实执行产出
     * @return 拼装后的提示词文本
     */
    private String buildSummaryPrompt(String question, String toolName, String output) {
        // 限制喂给大模型的文本大小，防止内容爆 token
        String sampleOutput = output;
        if (output.length() > MAX_SUMMARY_CHARS) {
            sampleOutput = output.substring(0, MAX_SUMMARY_CHARS) + "\n\n(由于内容超长，已为您截断前 3000 字...)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("用户原始提问：").append(question == null || question.isBlank() ? "(无)" : question).append("\n\n");
        sb.append("以下是工具 [").append(toolName).append("] 的真实运行返回结果：\n");
        sb.append("----------------------------\n");
        sb.append(sampleOutput).append("\n");
        sb.append("----------------------------\n\n");
        sb.append("请根据上述工具返回的数据，用一段自然友好且简洁的中文直接回答用户的问题；\n")
                .append("注意：直接给出回答即可，不要重复强调调用工具本身或复述内部技术参数，重点提炼核心结论。");
        return sb.toString();
    }

    /**
     * 逆序搜索会话历史获取最近一次的用户发问。
     *
     * @param conv 会话
     * @return 发问文本内容；无时返回空串
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
     * 将 AI 助手的回答持久化写入会话数据库。
     *
     * @param conv    会话，非空
     * @param content 拼装好包含 think 链和 Observation 的文本，非空
     */
    private void persistAssistantMessage(Conversation conv, String content) {
        ChatMessage aiMsg = new ChatMessage();
        aiMsg.setRole("assistant");
        aiMsg.setContent(content == null ? "" : content);
        aiMsg.setTimestamp(LocalDateTime.now());
        conv.getMessages().add(aiMsg);
        conv.setUpdateTime(LocalDateTime.now());
        chatService.save(conv);
    }

    /**
     * 将大模型流式总结产生的底层事件封装映射为 SSE 使用的帧事件。
     *
     * @param event       Agent 产生的事件
     * @param accumulator 历史累积器
     * @return 映射后的 SSE 帧实例
     */
    private ChatStreamEvent mapAgentEventToSse(Event event, StreamAccumulator accumulator) {
        EventType type = event.getType();
        String text = extractEventText(event);
        if (type == EventType.REASONING) {
            accumulator.appendReasoning(text);
            return new ChatStreamEvent("reasoning", text);
        }
        if (type == EventType.AGENT_RESULT) {
            accumulator.appendMessage(text);
            return new ChatStreamEvent("message", text);
        }
        accumulator.appendMessage(text);
        return new ChatStreamEvent("message", text);
    }

    /**
     * 从 Event 获取纯文本数据。
     *
     * @param event 事件，非空
     * @return 提取出的字符串，无时返回空串
     */
    private String extractEventText(Event event) {
        Msg msg = event.getMessage();
        if (msg == null) {
            return "";
        }
        String text = msg.getTextContent();
        return text != null ? text : "";
    }
}
