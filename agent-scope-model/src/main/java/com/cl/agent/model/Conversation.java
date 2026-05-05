package com.cl.agent.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import lombok.*;
import java.util.List;

/**
 * 会话实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("t_conversation")
public class Conversation extends BaseEntity {

    /** 会话唯一标识 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 会话标题 */
    @TableField("title")
    private String title;

    /** 关联的 Agent ID */
    @TableField("agent_id")
    private String agentId;

    /** 所属用户 ID */
    @TableField("user_id")
    private String userId;

    /** 聊天消息列表 (MyBatis 不自动关联) */
    @TableField(exist = false)
    private List<ChatMessage> messages;
}
