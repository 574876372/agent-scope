package com.cl.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 模型定义实体类。
 * <p>对应 MySQL 中的 {@code t_model} 表。描述某个厂商下可用的一个具体模型，
 * 按 {@code modelType} 区分对话模型（CHAT）与向量模型（EMBEDDING）。
 * 向量模型被知识库绑定后，其模型名、维度等影响向量空间的字段不可再修改。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("t_model")
public class ModelInfo extends BaseEntity {

    /** 模型唯一标识符 ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属厂商 ID，关联 t_model_provider.id */
    @TableField("provider_id")
    private String providerId;

    /** 模型用途类型，对应 ModelTypeEnum：CHAT / EMBEDDING */
    @TableField("model_type")
    private String modelType;

    /** 调用接口时使用的模型名称，如 qwen-plus、text-embedding-v2 */
    @TableField("model_name")
    private String modelName;

    /** 向量维度，仅 EMBEDDING 使用；须与模型实际输出一致，同时作为向量库索引维度 */
    @TableField("dimensions")
    private Integer dimensions;

    /** 是否将 dimensions 作为请求参数发给接口，仅 EMBEDDING 使用；1 是 0 否 */
    @TableField("send_dimensions")
    private Integer sendDimensions;

    /** 是否为同类型的默认模型；1 是 0 否，同一 modelType 下至多一个 */
    @TableField("is_default")
    private Integer isDefault;

    /** 是否启用 1 启用 0 停用 */
    @TableField("enabled")
    private Integer enabled;
}
