package com.cl.agent.dto;

import lombok.Data;
import java.io.Serializable;

/**
 * Agent 对话响应 DTO
 * 用于返回 Agent 回复的消息内容及来源信息
 */
@Data
public class ChatResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 响应消息所属的 Agent ID */
    private String agentId;

    /** 响应消息所属的 Agent 名称 */
    private String agentName;

    /** Agent 回复的消息内容 */
    private String content;
}
