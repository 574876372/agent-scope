package com.cl.agent.dto;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 知识库详细信息数据响应 DTO。
 * <p>面向前端展现知识库的主体元数据以及创建时间等要素，便于列表或配置卡片渲染。</p>
 */
@Data
public class KbResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 知识库唯一标识符 ID */
    private String id;

    /** 知识库名称 */
    private String name;

    /** 知识库描述 */
    private String description;

    /** 封面标识/头像 */
    private String avatar;

    /** 所属用户 ID */
    private String userId;

    /** 绑定的向量模型 ID */
    private String embeddingModelId;

    /** 绑定的向量模型名称，便于前端展示 */
    private String embeddingModelName;

    /** 知识库类型：GENERAL / TECH_DOC / FAQ / TABLE */
    private String kbType;

    /** 知识库类型中文名，如「技术 / 接口文档」 */
    private String kbTypeLabel;

    /** 知识库级切片策略；null 表示使用类型预设 */
    private String chunkStrategy;

    /** 知识库级切片大小；null 表示使用类型预设或全局默认 */
    private Integer chunkSize;

    /** 知识库级重叠字符数；null 表示使用全局默认 */
    private Integer chunkOverlap;

    /** 知识库级上下文扩展窗口；null 表示使用类型预设 */
    private Integer contextWindow;

    /** 实际生效的切片策略（知识库级 → 类型预设） */
    private String effectiveChunkStrategy;

    /** 实际生效的切片大小（知识库级 → 类型预设 → 全局默认） */
    private Integer effectiveChunkSize;

    /** 实际生效的重叠字符数 */
    private Integer effectiveChunkOverlap;

    /** 实际生效的上下文扩展窗口 */
    private Integer effectiveContextWindow;

    /** 创建时间 */
    private LocalDateTime createTime;
}
