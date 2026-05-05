package com.cl.agent.model;

import java.time.LocalDateTime;

/**
 * 单条聊天消息实体
 */
public class ChatMessage {

    private String role;       // user / assistant
    private String content;
    private LocalDateTime timestamp;

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}
