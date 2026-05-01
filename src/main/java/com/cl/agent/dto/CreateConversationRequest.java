package com.cl.agent.dto;

import java.io.Serializable;

import lombok.Data;

/**
 * 创建会话请求参数
 */
@Data
public class CreateConversationRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话标题，用于标识本次对话的主题 */
    private String title;
}
