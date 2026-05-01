package com.cl.agent.service;

import com.cl.agent.dto.*;

import java.util.List;

/**
 * 会话业务接口
 */
public interface IChatService {

    /** 创建新会话 */
    ConversationResponse createConversation(CreateConversationRequest request);

    /** 获取所有会话 */
    List<ConversationResponse> listConversations();

    /** 删除会话 */
    void deleteConversation(String conversationId);

    /** 发送消息并获取 AI 回复 */
    SendMessageResponse sendMessage(SendMessageRequest request);

    /** 获取会话历史消息 */
    List<ChatMessageResponse> getHistory(String conversationId);
}
