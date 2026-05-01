package com.cl.agent.service;

import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatService {

    private final Map<String, List<Map<String, Object>>> conversationHistory = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> conversations = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> sendMessage(String conversationId, String message) {
        try {
            // 如果没有指定对话ID，创建新对话
            if (conversationId == null || conversationId.trim().isEmpty()) {
                Map<String, Object> newConversation = createConversation("新对话");
                conversationId = (String) newConversation.get("id");
            }

            // 添加用户消息
            Map<String, Object> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", message);
            userMessage.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            conversationHistory.computeIfAbsent(conversationId, k -> new ArrayList<>()).add(userMessage);

            // 调用AgentScope处理消息
            String aiResponse = processMessageWithAgent(message);

            // 添加AI回复
            Map<String, Object> aiMessage = new HashMap<>();
            aiMessage.put("role", "assistant");
            aiMessage.put("content", aiResponse);
            aiMessage.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            conversationHistory.get(conversationId).add(aiMessage);

            // 更新对话标题（基于第一条消息）
            updateConversationTitle(conversationId, message);

            return Map.of(
                "conversationId", conversationId,
                "userMessage", userMessage,
                "aiMessage", aiMessage,
                "success", true
            );

        } catch (Exception e) {
            throw new RuntimeException("Failed to send message: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> getConversationHistory(String conversationId) {
        return conversationHistory.getOrDefault(conversationId, new ArrayList<>());
    }

    public Map<String, Object> createConversation(String title) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> conversation = new HashMap<>();
        conversation.put("id", id);
        conversation.put("title", title);
        conversation.put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        conversation.put("updatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        conversations.put(id, conversation);
        conversationHistory.put(id, new ArrayList<>());

        return conversation;
    }

    public List<Map<String, Object>> getAllConversations() {
        return new ArrayList<>(conversations.values());
    }

    public boolean deleteConversation(String conversationId) {
        conversations.remove(conversationId);
        conversationHistory.remove(conversationId);
        return true;
    }

    private String processMessageWithAgent(String message) {
        try {
            // 这里集成AgentScope的处理逻辑
            // 暂时返回模拟回复，后续替换为实际的AgentScope调用
            return generateMockResponse(message);
        } catch (Exception e) {
            return "抱歉，我遇到了一些问题，请稍后再试。";
        }
    }

    private String generateMockResponse(String message) {
        // 简单的模拟AI回复逻辑
        if (message.contains("你好") || message.contains("hello")) {
            return "您好！我是AI助手，很高兴为您服务。请问有什么可以帮助您的吗？";
        } else if (message.contains("天气")) {
            return "我暂时无法获取实时天气信息，但建议您查看天气预报应用或网站获取准确信息。";
        } else if (message.contains("时间")) {
            return "当前时间是：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } else {
            return "我理解您的问题。基于我的知识库，我会尽力为您提供有用的信息。如果您有具体的问题，请提供更多细节。";
        }
    }

    private void updateConversationTitle(String conversationId, String firstMessage) {
        Map<String, Object> conversation = conversations.get(conversationId);
        if (conversation != null && "新对话".equals(conversation.get("title"))) {
            // 根据第一条消息生成标题
            String title = generateTitleFromMessage(firstMessage);
            conversation.put("title", title);
            conversation.put("updatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
    }

    private String generateTitleFromMessage(String message) {
        if (message.length() > 20) {
            return message.substring(0, 20) + "...";
        }
        return message;
    }
}
