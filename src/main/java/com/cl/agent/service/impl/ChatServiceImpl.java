package com.cl.agent.service.impl;

import com.cl.agent.dto.*;
import com.cl.agent.exception.BizException;
import com.cl.agent.model.ChatMessage;
import com.cl.agent.model.Conversation;
import com.cl.agent.service.DemoServe;
import com.cl.agent.service.IChatService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.OpenAIChatModel;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ChatServiceImpl implements IChatService {

    /** 内存缓存：conversationId -> Conversation */
    private final ConcurrentHashMap<String, Conversation> conversationCache = new ConcurrentHashMap<>();

    /** AgentScope Agent，复用同一个实例 */
    private final Agent agent;

    public ChatServiceImpl() {
        OpenAIChatModel model = DemoServe.createMyModel();
        this.agent = ReActAgent.builder()
                .name("chat_agent")
                .model(model)
                .build();
    }

    @Override
    public ConversationResponse createConversation(CreateConversationRequest request) {
        Conversation conv = new Conversation();
        conv.setId(UUID.randomUUID().toString());
        conv.setTitle(request.getTitle() != null ? request.getTitle() : "新对话");
        conv.setMessages(new ArrayList<>());
        conv.setCreatedAt(LocalDateTime.now());
        conv.setUpdatedAt(LocalDateTime.now());

        conversationCache.put(conv.getId(), conv);
        return toConversationResponse(conv);
    }

    @Override
    public List<ConversationResponse> listConversations() {
        return conversationCache.values().stream()
                .map(this::toConversationResponse)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteConversation(String conversationId) {
        if (conversationCache.remove(conversationId) == null) {
            throw new BizException(404, "会话不存在: " + conversationId);
        }
    }

    @Override
    public SendMessageResponse sendMessage(SendMessageRequest request) {
        if (request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            throw new BizException(400, "消息内容不能为空");
        }

        // 没有 conversationId 时自动创建新会话
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.trim().isEmpty()) {
            CreateConversationRequest createReq = new CreateConversationRequest();
            createReq.setTitle(generateTitle(request.getMessage()));
            conversationId = createConversation(createReq).getId();
        }

        Conversation conv = conversationCache.get(conversationId);
        if (conv == null) {
            throw new BizException(404, "会话不存在: " + conversationId);
        }

        LocalDateTime now = LocalDateTime.now();

        // 记录用户消息
        ChatMessage userMsg = new ChatMessage();
        userMsg.setRole("user");
        userMsg.setContent(request.getMessage());
        userMsg.setTimestamp(now);
        conv.getMessages().add(userMsg);

        // 调用 AI（通过 AgentScope Agent 真实调用模型）
        String aiContent = callAgent(request.getMessage());

        // 记录 AI 消息
        ChatMessage aiMsg = new ChatMessage();
        aiMsg.setRole("assistant");
        aiMsg.setContent(aiContent);
        aiMsg.setTimestamp(LocalDateTime.now());
        conv.getMessages().add(aiMsg);

        conv.setUpdatedAt(LocalDateTime.now());

        SendMessageResponse resp = new SendMessageResponse();
        resp.setConversationId(conversationId);
        resp.setUserMessage(request.getMessage());
        resp.setAiMessage(aiContent);
        resp.setTimestamp(now);
        return resp;
    }

    @Override
    public List<ChatMessageResponse> getHistory(String conversationId) {
        Conversation conv = conversationCache.get(conversationId);
        if (conv == null) {
            throw new BizException(404, "会话不存在: " + conversationId);
        }
        return conv.getMessages().stream()
                .map(this::toChatMessageResponse)
                .collect(Collectors.toList());
    }

    // ---------- 私有方法 ----------

    /**
     * 调用 AgentScope Agent 获取 AI 回复
     */
    private String callAgent(String message) {
        Msg response = agent.call(
                Msg.builder()
                        .textContent(message)
                        .role(MsgRole.USER)
                        .build()
        ).block();
        return response != null ? response.getTextContent() : "AI 暂时无法响应，请稍后重试。";
    }

    private String generateTitle(String message) {
        return message.length() > 20 ? message.substring(0, 20) + "..." : message;
    }

    private ConversationResponse toConversationResponse(Conversation conv) {
        ConversationResponse resp = new ConversationResponse();
        resp.setId(conv.getId());
        resp.setTitle(conv.getTitle());
        resp.setCreatedAt(conv.getCreatedAt());
        resp.setUpdatedAt(conv.getUpdatedAt());
        return resp;
    }

    private ChatMessageResponse toChatMessageResponse(ChatMessage msg) {
        ChatMessageResponse resp = new ChatMessageResponse();
        resp.setRole(msg.getRole());
        resp.setContent(msg.getContent());
        resp.setTimestamp(msg.getTimestamp());
        return resp;
    }
}
