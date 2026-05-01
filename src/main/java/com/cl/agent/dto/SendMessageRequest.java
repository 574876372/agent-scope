package com.cl.agent.dto;

import java.io.Serializable;

import lombok.Data;

/**
 * 发送消息请求参数
 */
@Data
public class SendMessageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话 ID，为空时自动创建新会话 */
    private String conversationId;

    /** 用户发送的消息内容 */
    private String content;
}
