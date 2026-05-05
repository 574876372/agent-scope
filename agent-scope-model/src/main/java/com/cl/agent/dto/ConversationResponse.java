package com.cl.agent.dto;

import java.io.Serializable;
import java.time.LocalDateTime;

import lombok.Data;

/**
 * 会话响应 DTO
 * 用于返回会话的基本信息
 */
@Data
public class ConversationResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话唯一标识 */
    private String id;

    /** 会话标题 */
    private String title;

    /** 会话创建时间 */
    private LocalDateTime createTime;

    /** 会话最后更新时间 */
    private LocalDateTime updateTime;
}
