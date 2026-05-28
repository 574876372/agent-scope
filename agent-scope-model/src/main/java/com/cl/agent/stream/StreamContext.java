package com.cl.agent.stream;

import com.cl.agent.model.ChatMessage;
import com.cl.agent.model.Conversation;

import java.util.List;

/**
 * 流式会话上下文，封装单次流式对话所需的核心数据。
 * <p>由 {@code ChatBizImpl} 在准备阶段构建，贯穿整个 SSE 推送流程。</p>
 */
public class StreamContext {

    /** 完整会话对象（含历史消息列表），用于追加新消息并持久化 */
    private final Conversation conversation;

    /** 会话唯一标识，用于下发 {@code [CONV_ID]} 控制帧通知前端 */
    private final String conversationId;

    /** 本轮用户输入的文本内容，传递给 Agent 作为请求体 */
    private final String userContent;

    /**
     * 经过 {@code MemoryManager} 处理后的上下文消息列表。
     * <ul>
     *   <li>FULL 模式：全量历史消息</li>
     *   <li>WINDOW 模式：最近 N 轮消息</li>
     *   <li>SUMMARY 模式：[摘要 system 消息] + 最近 N 轮消息</li>
     * </ul>
     * 若为 null，则 Agent 调用时退化为仅传递当前用户输入（向后兼容）。
     */
    private final List<ChatMessage> contextMessages;

    /** 向后兼容构造器（不传 contextMessages） */
    public StreamContext(Conversation conversation, String conversationId, String userContent) {
        this(conversation, conversationId, userContent, null);
    }

    public StreamContext(Conversation conversation, String conversationId,
                         String userContent, List<ChatMessage> contextMessages) {
        this.conversation = conversation;
        this.conversationId = conversationId;
        this.userContent = userContent;
        this.contextMessages = contextMessages;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getUserContent() {
        return userContent;
    }

    public List<ChatMessage> getContextMessages() {
        return contextMessages;
    }
}
