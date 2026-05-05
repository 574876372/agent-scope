package com.cl.agent.biz.impl;

import com.cl.agent.biz.IAgentBiz;
import com.cl.agent.biz.IChatBiz;
import com.cl.agent.commons.UserContext;
import com.cl.agent.dto.*;
import com.cl.agent.exception.BizException;
import com.cl.agent.model.ChatMessage;
import com.cl.agent.model.Conversation;
import com.cl.agent.service.IChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ChatBizImpl implements IChatBiz {

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
        conv.setCreatedAt(LocalDateTime.now());
        conv.setUpdatedAt(LocalDateTime.now());
        conv.setUserId(UserContext.getUserId());

        chatService.save(conv);
        return toConversationResponse(conv);
    }

    @Override
    public List<ConversationResponse> listConversations() {
        String userId = UserContext.getUserId();
        return chatService.listAll().stream()
                .filter(conv -> userId == null || userId.equals(conv.getUserId()))
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

        String aiContent = "AI 暂时无法响应，请关联 Agent。";
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
        conv.setUpdatedAt(LocalDateTime.now());
        
        chatService.save(conv); // 更新存储

        SendMessageResponse resp = new SendMessageResponse();
        resp.setConversationId(conversationId);
        resp.setUserMessage(request.getContent());
        resp.setContent(aiContent);
        resp.setTimestamp(now);
        return resp;
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
