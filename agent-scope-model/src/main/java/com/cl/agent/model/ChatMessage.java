package com.cl.agent.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 单条聊天消息实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("t_chat_message")
public class ChatMessage extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色: user / assistant */
    @TableField("role")
    private String role;

    /** 消息内容 */
    @TableField("content")
    private String content;

    /** 消息时间 */
    @TableField("timestamp")
    private LocalDateTime timestamp;
    
    /** 所属会话 ID (用于关联) */
    @TableField("conversation_id")
    private String conversationId;
}
