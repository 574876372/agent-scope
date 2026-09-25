package com.cl.agent.dto.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 构建模型客户端所需的完整连接参数（服务端内部使用，含解密后的 API Key，禁止返回前端或打印日志）。
 *
 * <p>由 {@code IModelConfigService} 从厂商与模型两张表组装而成。{@link #version} 是全部连接参数的
 * 内容指纹：任一参数变化（如更换密钥）都会得到新的 version，调用方据此判断缓存的客户端是否需要重建。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelConnection {

    /** 模型 ID；对话模型未在模型表登记时为 null */
    private String modelId;

    /** 厂商编码 */
    private String providerCode;

    /** 接口协议 */
    private String protocol;

    /** 接口基础地址 */
    private String baseUrl;

    /** 解密后的 API Key 明文 */
    private String apiKey;

    /** 模型名称 */
    private String modelName;

    /** 向量维度（EMBEDDING） */
    private Integer dimensions;

    /** 是否将维度作为请求参数发送（EMBEDDING） */
    private boolean sendDimensions;

    /** 连接参数内容指纹 */
    private String version;

    @Override
    public String toString() {
        // 显式覆盖，避免 API Key 随对象被打印到日志
        return "ModelConnection(modelId=" + modelId + ", providerCode=" + providerCode + ", baseUrl=" + baseUrl
                + ", modelName=" + modelName + ", dimensions=" + dimensions + ")";
    }
}
