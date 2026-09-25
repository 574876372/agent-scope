package com.cl.agent.dto.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 模型响应 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelInfoResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模型 ID */
    private String id;

    /** 所属厂商 ID */
    private String providerId;

    /** 所属厂商显示名称 */
    private String providerName;

    /** 模型类型：CHAT / EMBEDDING */
    private String modelType;

    /** 模型名称 */
    private String modelName;

    /** 向量维度（EMBEDDING） */
    private Integer dimensions;

    /** 是否将维度作为请求参数发送（EMBEDDING） */
    private Integer sendDimensions;

    /** 是否为默认模型 */
    private Integer isDefault;

    /** 是否启用 */
    private Integer enabled;

    /** 绑定该模型的知识库数量（EMBEDDING）；大于 0 时模型名与维度不可修改 */
    private Long boundKbCount;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 最近更新时间 */
    private LocalDateTime updateTime;
}
