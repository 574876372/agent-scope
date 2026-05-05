package com.cl.agent.biz;

import com.cl.agent.dto.ChatMessageResponse;
import com.cl.agent.dto.ConversationResponse;
import com.cl.agent.dto.CreateConversationRequest;
import com.cl.agent.dto.SendMessageRequest;
import com.cl.agent.dto.SendMessageResponse;
import java.util.List;

public interface IChatBiz {
    ConversationResponse createConversation(CreateConversationRequest request);
    List<ConversationResponse> listConversations();
    void deleteConversation(String conversationId);
    SendMessageResponse sendMessage(SendMessageRequest request);
    List<ChatMessageResponse> getHistory(String conversationId);
}
