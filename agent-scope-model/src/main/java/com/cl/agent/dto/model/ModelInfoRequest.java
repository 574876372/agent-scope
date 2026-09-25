package com.cl.agent.dto.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 模型新增/编辑入参 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelInfoRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模型 ID，新增时为空，编辑时必填 */
    private String id;

    /** 所属厂商 ID，必填 */
    private String providerId;

    /** 模型类型：CHAT / EMBEDDING，必填 */
    private String modelType;

    /** 模型名称，必填 */
    private String modelName;

    /** 向量维度，EMBEDDING 必填 */
    private Integer dimensions;

    /** 是否将维度作为请求参数发送，EMBEDDING 使用；null 时默认 0 */
    private Integer sendDimensions;

    /** 是否设为同类型默认模型，null 时默认 0 */
    private Integer isDefault;

    /** 是否启用，null 时默认 1 */
    private Integer enabled;
}
