package com.cl.agent.stream;

import com.cl.agent.model.Conversation;

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

    public StreamContext(Conversation conversation, String conversationId, String userContent) {
        this.conversation = conversation;
        this.conversationId = conversationId;
        this.userContent = userContent;
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
}
