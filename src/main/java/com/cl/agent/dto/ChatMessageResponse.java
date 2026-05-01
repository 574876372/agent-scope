package com.cl.agent.dto;

import java.io.Serializable;
import java.time.LocalDateTime;

import lombok.Data;

/**
 * 单条聊天消息响应 DTO
 * 用于返回会话历史中的单条消息记录
 */
@Data
public class ChatMessageResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 消息角色，如 user（用户）/ assistant（AI） */
    private String role;

    /** 消息正文内容 */
    private String content;

    /** 消息发送时间 */
    private LocalDateTime timestamp;
}
