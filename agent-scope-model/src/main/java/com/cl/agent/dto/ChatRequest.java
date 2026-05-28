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
}
