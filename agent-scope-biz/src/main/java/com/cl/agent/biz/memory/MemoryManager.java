package com.cl.agent.biz.memory;

import com.cl.agent.biz.IAgentBiz;
import com.cl.agent.config.agent.AgentMemoryProperties;
import com.cl.agent.dto.ChatRequest;
import com.cl.agent.dto.ChatResponse;
import com.cl.agent.enums.MemoryMode;
import com.cl.agent.model.AgentInfo;
import com.cl.agent.model.ChatMessage;
import com.cl.agent.model.Conversation;
import com.cl.agent.model.ConversationSummary;
import com.cl.agent.service.IConversationSummaryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 记忆管理器
 * <p>根据 Agent 配置的 {@link MemoryMode}，对会话历史消息进行处理后返回上下文列表：</p>
 * <ul>
 *   <li>{@link MemoryMode#FULL}：返回全量历史，不做任何裁剪</li>
 *   <li>{@link MemoryMode#WINDOW}：超限时纯滑动，丢弃旧消息</li>
 *   <li>{@link MemoryMode#SUMMARY}：超限时先摘要旧消息并持久化，再返回摘要+最近 N 轮（默认）</li>
 * </ul>
 */
@Component
@Slf4j
public class MemoryManager {

    @Autowired
    private AgentMemoryProperties memoryProperties;

    @Autowired
    private IConversationSummaryService summaryService;

    /**
     * 延迟注入，避免与 AgentBizImpl 循环依赖（MemoryManager -> IAgentBiz -> MemoryManager）。
     * Spring 通过 setter 注入解决循环引用。
     */
    private IAgentBiz agentBiz;

    @Autowired
    public void setAgentBiz(IAgentBiz agentBiz) {
        this.agentBiz = agentBiz;
    }

    /**
     * 根据会话历史和 Agent 配置，返回处理后的上下文消息列表。
     *
     * @param conv  当前会话（含全量历史消息）
     * @param agent 当前 Agent 信息（含 memoryMode / maxTurns 配置）
     * @return 处理后的上下文消息列表，可直接作为 Agent 调用的历史上下文
     */
    public List<ChatMessage> resolveContext(Conversation conv, AgentInfo agent) {
        if (conv == null || conv.getMessages() == null || conv.getMessages().isEmpty()) {
            return new ArrayList<>();
        }

        MemoryMode mode = MemoryMode.of(agent != null ? agent.getMemoryMode() : null);
        if (mode == null) {
            mode = memoryProperties.getDefaultMode();
        }

        // 按时间升序排列（最旧消息在前）
        List<ChatMessage> allMessages = conv.getMessages().stream()
                .filter(m -> m != null)
                .sorted(Comparator.comparing(
                        m -> m.getTimestamp() != null ? m.getTimestamp() : LocalDateTime.MIN))
                .collect(Collectors.toList());

        // 计算完整轮数（user + assistant 配对）
        int totalMessages = allMessages.size();
        int totalTurns = totalMessages / 2;

        int effectiveMaxTurns = (agent != null && agent.getMaxTurns() != null)
                ? agent.getMaxTurns()
                : memoryProperties.getMaxTurns();

        log.info("[Memory] 解析上下文 - 会话ID: {}, 记忆模式: {}, 当前总消息数: {}, 对话总轮数: {}, 配置的最大轮数: {}",
                conv.getId(), mode, totalMessages, totalTurns, effectiveMaxTurns);

        // ── FULL 模式：全量返回 ──────────────────────────────────────────
        if (mode == MemoryMode.FULL) {
            return new ArrayList<>(allMessages);
        }

        if (totalTurns <= effectiveMaxTurns) {
            log.debug("[Memory] {} 模式-未超限, conversationId={}, totalTurns={}, maxTurns={}",
                    mode, conv.getId(), totalTurns, effectiveMaxTurns);
            return new ArrayList<>(allMessages);
        }

        // ── WINDOW 模式：纯截取，不摘要 ────────────────────────────────
        if (mode == MemoryMode.WINDOW) {
            int keepCount = effectiveMaxTurns * 2;
            List<ChatMessage> windowed = allMessages.subList(allMessages.size() - keepCount, allMessages.size());
            log.info("[Memory] WINDOW 模式-截取, conversationId={}, totalTurns={}, maxTurns={}, kept={}",
                    conv.getId(), totalTurns, effectiveMaxTurns, keepCount);
            return new ArrayList<>(windowed);
        }

        // ── SUMMARY 模式：摘要旧消息 + 最近 N 轮 ───────────────────────
        return resolveSummaryContext(conv, agent, allMessages, effectiveMaxTurns, totalTurns);
    }

    /**
     * SUMMARY 模式核心：增量摘要 + 上下文拼装。
     */
    private List<ChatMessage> resolveSummaryContext(Conversation conv, AgentInfo agent,
                                                     List<ChatMessage> allMessages,
                                                     int effectiveMaxTurns, int totalTurns) {
        // 需要保留的最近 N 轮消息
        int keepCount = effectiveMaxTurns * 2;
        List<ChatMessage> recentMessages = new ArrayList<>(
                allMessages.subList(allMessages.size() - keepCount, allMessages.size()));

        // 待摘要的旧消息（最近 N 轮之前的部分）
        List<ChatMessage> oldMessages = allMessages.subList(0, allMessages.size() - keepCount);

        // 查询已存在的最新摘要
        ConversationSummary latestSummary = summaryService.getLatest(conv.getId());
        long coveredUpTo = latestSummary != null ? latestSummary.getCoveredUpTo() : -1L;

        // 过滤出尚未被摘要覆盖的增量消息
        List<ChatMessage> newToSummarize = oldMessages.stream()
                .filter(m -> m.getId() != null && m.getId() > coveredUpTo)
                .collect(Collectors.toList());

        String latestSummaryText = latestSummary != null ? latestSummary.getSummary() : null;

        if (!newToSummarize.isEmpty() && agent != null) {
            try {
                String summaryPrompt = buildSummaryPrompt(latestSummaryText, newToSummarize);
                log.info("[Memory] SUMMARY 模式-触发摘要压缩, conversationId={}, toSummarize={} messages",
                        conv.getId(), newToSummarize.size());

                ChatRequest req = new ChatRequest();
                req.setContent(summaryPrompt);
                ChatResponse response = agentBiz.chat(agent.getId(), req);

                String newSummaryText = response != null ? response.getContent() : "";
                if (newSummaryText != null && !newSummaryText.isBlank()) {
                    Long lastId = newToSummarize.stream()
                            .map(ChatMessage::getId)
                            .filter(id -> id != null)
                            .max(Long::compareTo)
                            .orElse(coveredUpTo);
                    summaryService.save(conv.getId(), newSummaryText, lastId);
                    latestSummaryText = newSummaryText;
                    log.info("[Memory] SUMMARY 模式-摘要完成, conversationId={}, coveredUpTo={}",
                            conv.getId(), lastId);
                }
            } catch (Exception e) {
                // 摘要失败不阻断主流程，退化为 WINDOW 行为
                log.warn("[Memory] SUMMARY 模式-摘要调用失败，退化为纯滑动窗口, conversationId={}, error={}",
                        conv.getId(), e.getMessage());
            }
        }

        // 构建最终上下文：[摘要 system 消息] + 最近 N 轮
        List<ChatMessage> context = new ArrayList<>();
        if (latestSummaryText != null && !latestSummaryText.isBlank()) {
            ChatMessage summaryMsg = new ChatMessage();
            summaryMsg.setRole("system");
            summaryMsg.setContent("[历史对话摘要]\n" + latestSummaryText);
            summaryMsg.setTimestamp(LocalDateTime.MIN);
            context.add(summaryMsg);
        }
        context.addAll(recentMessages);

        log.debug("[Memory] SUMMARY 模式-上下文构建完成, conversationId={}, contextSize={}",
                conv.getId(), context.size());
        return context;
    }

    /**
     * 构建发送给 LLM 的摘要 Prompt。
     *
     * @param existingSummary 已有摘要文本（可为 null）
     * @param messages        本次需要新摘要的消息列表
     * @return 摘要请求 Prompt
     */
    private String buildSummaryPrompt(String existingSummary, List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("请将以下对话历史进行简洁的摘要总结，保留关键信息和用户意图，以便后续对话能够引用：\n\n");

        if (existingSummary != null && !existingSummary.isBlank()) {
            sb.append("【已有历史摘要】\n").append(existingSummary).append("\n\n");
            sb.append("【需要追加摘要的新对话】\n");
        } else {
            sb.append("【待摘要的对话历史】\n");
        }

        for (ChatMessage msg : messages) {
            String role = "user".equals(msg.getRole()) ? "用户" : "助手";
            // 摘要时跳过已有摘要注入的 system 消息
            if ("system".equals(msg.getRole())) continue;
            sb.append(role).append(": ").append(msg.getContent()).append("\n");
        }

        sb.append("\n请输出一段简洁的摘要（不超过 300 字）：");
        return sb.toString();
    }
}
