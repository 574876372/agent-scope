package com.cl.agent.dto;

import com.cl.agent.model.ChatMessage;
import lombok.Data;
import java.io.Serializable;
import java.util.List;

/**
 * 向 Agent 发送消息的请求参数
 */
@Data
public class ChatRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 发送给 Agent 的消息内容 */
    private String content;

    /** 经过记忆管理裁减后的历史对话上下文消息列表 */
    private List<ChatMessage> history;

    /**
     * GENERIC 模式下由业务层前置检索得到的知识库上下文（{@code <retrieved_knowledge>} 包裹）；
     * 非空时作为一条独立的用户消息紧跟在本轮问题之后发送给模型，不写入会话历史。为空表示无检索结果或未启用 RAG。
     */
    private String knowledgeContext;
}
