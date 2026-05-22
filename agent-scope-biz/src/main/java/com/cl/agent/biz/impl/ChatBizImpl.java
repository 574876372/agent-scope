package com.cl.agent.biz.impl;

import com.cl.agent.biz.IAgentBiz;
import com.cl.agent.biz.IChatBiz;
import com.cl.agent.commons.UserContext;
import com.cl.agent.dto.*;
import com.cl.agent.exception.BizException;
import com.cl.agent.model.ChatMessage;
import com.cl.agent.model.Conversation;
import com.cl.agent.service.IChatService;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 对话业务实现：流式接口将 AgentScope Event 映射为 SSE（reasoning / tool_result / message）。
 */
@Service
@Slf4j
public class ChatBizImpl implements IChatBiz {

    private static final String PLACEHOLDER_NO_AGENT = "AI 暂时无法响应，请关联 Agent。";
    private static final Pattern TOOL_JSON_PATTERN =
            Pattern.compile("\"tool\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"output\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Autowired
    private IChatService chatService;

    @Autowired
    private IAgentBiz agentBiz;

    @Override
    public ConversationResponse createConversation(CreateConversationRequest request) {
        Conversation conv = new Conversation();
        conv.setId(UUID.randomUUID().toString());
        conv.setTitle(request.getTitle() != null ? request.getTitle() : "新对话");
        conv.setAgentId(request.getAgentId());
        conv.setMessages(new ArrayList<>());
        conv.setUserId(UserContext.getUserId());

        chatService.save(conv);
        return toConversationResponse(conv);
    }

    @Override
    public List<ConversationResponse> listConversations() {
        String userId = UserContext.getUserId();
        return chatService.listByUserId(userId).stream()
                .map(this::toConversationResponse)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteConversation(String conversationId) {
        chatService.deleteById(conversationId);
    }

    @Override
    public SendMessageResponse sendMessage(SendMessageRequest request) {
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.trim().isEmpty() || "undefined".equals(conversationId)) {
            CreateConversationRequest createReq = new CreateConversationRequest();
            createReq.setTitle(generateTitle(request.getContent()));
            conversationId = createConversation(createReq).getId();
        }

        Conversation conv = chatService.getById(conversationId);
        if (conv == null) {
            throw new BizException(404, "会话不存在: " + conversationId);
        }

        LocalDateTime now = LocalDateTime.now();
        ChatMessage userMsg = new ChatMessage();
        userMsg.setRole("user");
        userMsg.setContent(request.getContent());
        userMsg.setTimestamp(now);
        conv.getMessages().add(userMsg);

        String aiContent = PLACEHOLDER_NO_AGENT;
        if (conv.getAgentId() != null) {
            ChatRequest chatRequest = new ChatRequest();
            chatRequest.setContent(request.getContent());
            ChatResponse chatResponse = agentBiz.chat(conv.getAgentId(), chatRequest);
            aiContent = chatResponse.getContent();
        }

        ChatMessage aiMsg = new ChatMessage();
        aiMsg.setRole("assistant");
        aiMsg.setContent(aiContent);
        aiMsg.setTimestamp(LocalDateTime.now());
        conv.getMessages().add(aiMsg);
        conv.setUpdateTime(LocalDateTime.now());

        chatService.save(conv);

        SendMessageResponse resp = new SendMessageResponse();
        resp.setConversationId(conversationId);
        resp.setUserMessage(request.getContent());
        resp.setContent(aiContent);
        resp.setTimestamp(now);
        return resp;
    }

    @Override
    public Flux<ChatStreamEvent> sendMessageStream(SendMessageRequest request) {
        final String resolvedConversationId = resolveConversationId(request);

        return Mono.fromCallable(() -> prepareStreamContext(resolvedConversationId, request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(this::buildAgentStreamFlux)
                .onErrorResume(e -> {
                    log.error("[Flux] 流式消息推送异常", e);
                    String msg = e.getMessage() != null ? e.getMessage() : "流式对话失败";
                    return Flux.just(new ChatStreamEvent("error", msg));
                });
    }

    /**
     * 解析或创建会话 ID。
     */
    private String resolveConversationId(SendMessageRequest request) {
        String rawConvId = request.getConversationId();
        if (rawConvId == null || rawConvId.trim().isEmpty() || "undefined".equals(rawConvId)) {
            CreateConversationRequest createReq = new CreateConversationRequest();
            createReq.setTitle(generateTitle(request.getContent()));
            return createConversation(createReq).getId();
        }
        return rawConvId;
    }

    /**
     * 加载会话并追加用户消息。
     */
    private StreamContext prepareStreamContext(String conversationId, SendMessageRequest request) {
        Conversation conv = chatService.getById(conversationId);
        if (conv == null) {
            throw new BizException(404, "会话不存在: " + conversationId);
        }
        LocalDateTime now = LocalDateTime.now();
        ChatMessage userMsg = new ChatMessage();
        userMsg.setRole("user");
        userMsg.setContent(request.getContent());
        userMsg.setTimestamp(now);
        conv.getMessages().add(userMsg);
        chatService.save(conv);
        return new StreamContext(conv, conversationId, request.getContent());
    }

    /**
     * 构建完整 SSE 流：控制事件 + Agent 事件 + 持久化 + [DONE]。
     */
    private Flux<ChatStreamEvent> buildAgentStreamFlux(StreamContext ctx) {
        Flux<ChatStreamEvent> convIdEvent = Flux.just(
                new ChatStreamEvent(null, "[CONV_ID]" + ctx.conversationId));

        if (ctx.conversation.getAgentId() == null) {
            return Flux.concat(
                    convIdEvent,
                    Flux.just(messageEvent(PLACEHOLDER_NO_AGENT)),
                    Mono.fromRunnable(() -> persistAssistantMessage(ctx.conversation, PLACEHOLDER_NO_AGENT))
                            .subscribeOn(Schedulers.boundedElastic())
                            .thenMany(Flux.just(doneEvent())));
        }

        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setContent(ctx.userContent);
        StreamAccumulator accumulator = new StreamAccumulator();

        Flux<ChatStreamEvent> agentEvents = agentBiz.chatStream(ctx.conversation.getAgentId(), chatRequest)
                .map(event -> mapAgentEventToSse(event, accumulator));

        return convIdEvent.concatWith(agentEvents)
                .concatWith(Flux.defer(() -> {
                    String persistContent = accumulator.buildPersistContent();
                    if (persistContent.isEmpty()) {
                        persistContent = PLACEHOLDER_NO_AGENT;
                    }
                    persistAssistantMessage(ctx.conversation, persistContent);
                    return Flux.just(doneEvent());
                }));
    }

    private ChatStreamEvent mapAgentEventToSse(Event event, StreamAccumulator accumulator) {
        EventType type = event.getType();
        String text = extractEventText(event);

        if (type == EventType.REASONING) {
            accumulator.appendReasoning(text);
            return new ChatStreamEvent("reasoning", text);
        }
        if (type == EventType.TOOL_RESULT) {
            String json = formatToolResultJson(event);
            accumulator.appendToolResultJson(json);
            return new ChatStreamEvent("tool_result", json);
        }
        if (type == EventType.AGENT_RESULT) {
            accumulator.appendMessage(text);
            return new ChatStreamEvent("message", text);
        }
        accumulator.appendMessage(text);
        return messageEvent(text);
    }

    private static String extractEventText(Event event) {
        Msg msg = event.getMessage();
        if (msg == null) {
            return "";
        }
        String text = msg.getTextContent();
        return text != null ? text : "";
    }

    /**
     * 将工具结果事件序列化为前端可解析的 JSON。
     */
    private String formatToolResultJson(Event event) {
        Msg msg = event.getMessage();
        if (msg == null) {
            return "{\"tool\":\"tool\",\"output\":\"\"}";
        }
        String tool = "tool";
        String output = extractEventText(event);
        List<ToolResultBlock> blocks = msg.getContentBlocks(ToolResultBlock.class);
        if (!blocks.isEmpty()) {
            ToolResultBlock block = blocks.get(0);
            if (block.getName() != null && !block.getName().isBlank()) {
                tool = block.getName();
            }
        }
        return "{\"tool\":\"" + jsonEscape(tool) + "\",\"output\":\"" + jsonEscape(output) + "\"}";
    }

    private static ChatStreamEvent messageEvent(String data) {
        return new ChatStreamEvent("message", data);
    }

    private static ChatStreamEvent doneEvent() {
        return new ChatStreamEvent(null, "[DONE]");
    }

    private void persistAssistantMessage(Conversation conv, String content) {
        ChatMessage aiMsg = new ChatMessage();
        aiMsg.setRole("assistant");
        aiMsg.setContent(content);
        aiMsg.setTimestamp(LocalDateTime.now());
        conv.getMessages().add(aiMsg);
        conv.setUpdateTime(LocalDateTime.now());
        chatService.save(conv);
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String unescapeJsonString(String escaped) {
        if (escaped == null) {
            return "";
        }
        return escaped
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    @Override
    public List<ChatMessageResponse> getHistory(String conversationId) {
        Conversation conv = chatService.getById(conversationId);
        if (conv == null) {
            throw new BizException(404, "会话不存在: " + conversationId);
        }
        return conv.getMessages().stream()
                .map(this::toChatMessageResponse)
                .collect(Collectors.toList());
    }

    private String generateTitle(String message) {
        return message.length() > 20 ? message.substring(0, 20) + "..." : message;
    }

    private ConversationResponse toConversationResponse(Conversation conv) {
        ConversationResponse resp = new ConversationResponse();
        resp.setId(conv.getId());
        resp.setTitle(conv.getTitle());
        resp.setCreateTime(conv.getCreateTime());
        resp.setUpdateTime(conv.getUpdateTime());
        return resp;
    }

    private ChatMessageResponse toChatMessageResponse(ChatMessage msg) {
        ChatMessageResponse resp = new ChatMessageResponse();
        resp.setRole(msg.getRole());
        resp.setContent(msg.getContent());
        resp.setTimestamp(msg.getTimestamp());
        return resp;
    }

    /** 流式会话上下文 */
    private static final class StreamContext {
        final Conversation conversation;
        final String conversationId;
        final String userContent;

        StreamContext(Conversation conversation, String conversationId, String userContent) {
            this.conversation = conversation;
            this.conversationId = conversationId;
            this.userContent = userContent;
        }
    }

    /**
     * 累积推理/工具/回复片段，用于持久化可被前端 parser 解析的完整文本。
     */
    private static final class StreamAccumulator {
        private final StringBuilder reasoning = new StringBuilder();
        private final StringBuilder tools = new StringBuilder();
        private final StringBuilder message = new StringBuilder();

        void appendReasoning(String chunk) {
            if (chunk != null && !chunk.isEmpty()) {
                reasoning.append(chunk);
            }
        }

        void appendMessage(String chunk) {
            if (chunk != null && !chunk.isEmpty()) {
                message.append(chunk);
            }
        }

        void appendToolResultJson(String json) {
            Matcher m = TOOL_JSON_PATTERN.matcher(json);
            if (m.find()) {
                String tool = unescapeJsonString(m.group(1));
                String output = unescapeJsonString(m.group(2));
                tools.append("Action: ").append(tool).append("\nObservation: ").append(output).append("\n");
            }
        }

        String buildPersistContent() {
            StringBuilder sb = new StringBuilder();
            if (reasoning.length() > 0) {
                sb.append("<think>").append(reasoning).append("</think>");
            }
            sb.append(tools);
            sb.append(message);
            return sb.toString();
        }
    }
}
