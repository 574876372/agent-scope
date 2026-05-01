package com.cl.agent.service.impl;

import com.cl.agent.dto.*;
import com.cl.agent.exception.BizException;
import com.cl.agent.model.ChatMessage;
import com.cl.agent.model.Conversation;
import com.cl.agent.service.IAgentService;
import com.cl.agent.service.IChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.cl.agent.commons.UserContext;

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

    @Autowired
    private IAgentService agentService;

    @Override
    public ConversationResponse createConversation(CreateConversationRequest request) {
        Conversation conv = new Conversation();
        conv.setId(UUID.randomUUID().toString());
        conv.setTitle(request.getTitle() != null ? request.getTitle() : "新对话");
        conv.setAgentId(request.getAgentId()); // 保存关联的 AgentId
        conv.setMessages(new ArrayList<>());
        conv.setCreatedAt(LocalDateTime.now());
        conv.setUpdatedAt(LocalDateTime.now());
        conv.setUserId(UserContext.getUserId());

        conversationCache.put(conv.getId(), conv);
        return toConversationResponse(conv);
    }

    @Override
    public List<ConversationResponse> listConversations() {
        String userId = UserContext.getUserId();
        return conversationCache.values().stream()
                .filter(conv -> userId == null || userId.equals(conv.getUserId()))
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
        if (request.getContent() == null || request.getContent().trim().isEmpty()) {
            throw new BizException(400, "消息内容不能为空");
        }

        // 没有 conversationId 时自动创建新会话
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.trim().isEmpty() || "undefined".equals(conversationId)) {
            CreateConversationRequest createReq = new CreateConversationRequest();
            createReq.setTitle(generateTitle(request.getContent()));
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
        userMsg.setContent(request.getContent());
        userMsg.setTimestamp(now);
        conv.getMessages().add(userMsg);

        // 调用关联的 Agent（通过 agentService 获取对应的实例并对话）
        String aiContent = "AI 暂时无法响应，请关联 Agent。";
        if (conv.getAgentId() != null) {
            ChatRequest chatRequest = new ChatRequest();
            chatRequest.setContent(request.getContent());
            ChatResponse chatResponse = agentService.chat(conv.getAgentId(), chatRequest);
            aiContent = chatResponse.getContent();
        }

        if (aiContent != null) {
            aiContent = aiContent.trim();
        }

        // 记录 AI 消息
        ChatMessage aiMsg = new ChatMessage();
        aiMsg.setRole("assistant");
        aiMsg.setContent(aiContent);
        aiMsg.setTimestamp(LocalDateTime.now());
        conv.getMessages().add(aiMsg);

        conv.setUpdatedAt(LocalDateTime.now());

        SendMessageResponse resp = new SendMessageResponse();
        resp.setConversationId(conversationId);
        resp.setUserMessage(request.getContent());
        resp.setContent(aiContent);
        resp.setTimestamp(now);
        return resp;
    }

    @Override
    public List<ChatMessageResponse> getHistory(String conversationId) {
        if (conversationId == null || "undefined".equals(conversationId)) {
            return new ArrayList<>();
        }
        Conversation conv = conversationCache.get(conversationId);
        if (conv == null) {
            throw new BizException(404, "会话不存在: " + conversationId);
        }
        return conv.getMessages().stream()
                .map(this::toChatMessageResponse)
                .collect(Collectors.toList());
    }

    // ---------- 私有方法 ----------

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
