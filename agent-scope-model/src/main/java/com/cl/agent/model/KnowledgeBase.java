package com.cl.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 知识库（Knowledge Base）基础信息实体类。
 * <p>对应 MySQL 中的 {@code t_knowledge_base} 表，继承自 {@link BaseEntity} 包含统一的审计及逻辑删除字段。
 * 用于定义和管理系统中独立逻辑隔离的知识库元数据主体。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("t_knowledge_base")
public class KnowledgeBase extends BaseEntity {

    /** 唯一标识符 ID */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 知识库名称 */
    @TableField("name")
    private String name;

    /** 知识库详细业务描述 */
    @TableField("description")
    private String description;

    /** 知识库封面头像链接/标识 */
    @TableField("avatar")
    private String avatar;

    /** 创建并拥有此知识库的用户 ID */
    @TableField("user_id")
    private String userId;
}
