package com.cl.agent.dto.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 模型厂商新增/编辑入参 DTO。
 *
 * <p>apiKeyPlain 仅用于"录入/更换密钥"，从不返回给前端；编辑时为空表示不修改密钥。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelProviderRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 厂商 ID，新增时为空，编辑时必填 */
    private String id;

    /** 厂商编码，新增必填，创建后不可修改（智能体通过该值引用厂商） */
    private String code;

    /** 显示名称，必填 */
    private String name;

    /** 接口协议，null 时默认 OPENAI */
    private String protocol;

    /** 接口基础地址，必填 */
    private String baseUrl;

    /** API Key 明文：为空 = 不修改（新增时为空表示暂不配置） */
    private String apiKeyPlain;

    /** 是否启用，null 时默认 1 */
    private Integer enabled;
}
