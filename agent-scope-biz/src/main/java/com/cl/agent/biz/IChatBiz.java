package com.cl.agent.biz;

import com.cl.agent.dto.ChatMessageResponse;
import com.cl.agent.dto.ConversationResponse;
import com.cl.agent.dto.CreateConversationRequest;
import com.cl.agent.dto.SendMessageRequest;
import com.cl.agent.dto.SendMessageResponse;
import reactor.core.publisher.Flux;
import java.util.List;

/**
 * 对话业务逻辑接口
 * 负责维护聊天会话、消息发送以及历史记录查询
 */
public interface IChatBiz {

    /**
     * 创建一个新的聊天会话
     * 
     * @param request 创建会话请求参数
     * @return 创建成功的会话响应信息
     */
    ConversationResponse createConversation(CreateConversationRequest request);

    /**
     * 获取当前用户的所有会话列表
     * 
     * @return 会话列表
     */
    List<ConversationResponse> listConversations();

    /**
     * 删除指定 ID 的会话
     * 
     * @param conversationId 会话 ID
     */
    void deleteConversation(String conversationId);

    /**
     * 发送聊天消息并获取 AI 响应
     * 
     * @param request 发送消息请求参数
     * @return 包含用户消息和 AI 响应的结果
     */
    SendMessageResponse sendMessage(SendMessageRequest request);

    /**
     * 以响应式流（Flux）方式发送消息，AI 响应内容将逐块推送给客户端
     *
     * @param request 发送消息请求参数
     * @return Flux&lt;String&gt;，每个元素为一段 AI 回复文本
     */
    Flux<String> sendMessageStream(SendMessageRequest request);

    /**
     * 获取指定会话的历史消息记录
     * 
     * @param conversationId 会话 ID
     * @return 历史消息列表
     */
    List<ChatMessageResponse> getHistory(String conversationId);
}
