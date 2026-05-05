package com.cl.agent.dto;

import lombok.Data;
import java.io.Serializable;

/**
 * 向 Agent 发送消息的请求参数
 */
@Data
public class ChatRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 发送给 Agent 的消息内容 */
    private String content;
}
