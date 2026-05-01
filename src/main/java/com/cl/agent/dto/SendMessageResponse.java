package com.cl.agent.dto;

import java.io.Serializable;
import java.time.LocalDateTime;

import lombok.Data;

/**
 * 发送消息响应 DTO
 * 用于返回消息发送后的完整对话记录
 */
@Data
public class SendMessageResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 本次对话所属的会话 ID */
    private String conversationId;

    /** 用户发送的原始消息 */
    private String userMessage;

    /** AI 回复的消息内容 */
    private String aiMessage;

    /** 消息发送时间戳 */
    private LocalDateTime timestamp;
}
