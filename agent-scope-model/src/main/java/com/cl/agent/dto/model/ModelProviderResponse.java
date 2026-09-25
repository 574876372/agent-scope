package com.cl.agent.dto.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 模型厂商响应 DTO。
 *
 * <p>出于安全考量，密钥的密文与明文均不下发，仅通过 {@link #apiKeyConfigured} 告知是否已配置。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelProviderResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 厂商 ID */
    private String id;

    /** 厂商编码 */
    private String code;

    /** 显示名称 */
    private String name;

    /** 接口协议 */
    private String protocol;

    /** 接口基础地址 */
    private String baseUrl;

    /** 是否已配置 API Key */
    private Boolean apiKeyConfigured;

    /** 是否启用 */
    private Integer enabled;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 最近更新时间 */
    private LocalDateTime updateTime;
}
