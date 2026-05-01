package com.cl.agent.enums;

import com.cl.agent.exception.BizException;

/**
 * 模型厂商枚举
 * 统一管理各厂商的接口地址和鉴权信息，新增厂商只需在此追加枚举值
 */
public enum ModelProviderEnum {

    /** 通义千问（阿里云） */
    QWEN("Qwen", "https://aiapi.oldbird.tech/v1", "sk-c95e548188d16bed66cc5e5880969f056a3ffeea9dc976b4"),

    /** DeepSeek */
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "sk-3133ef74c49c44ddbfc230343f38d4f0");

    /** 厂商标识，与请求参数 modelType 对应，不区分大小写 */
    private final String type;

    /** 模型接口基础地址 */
    private final String baseUrl;

    /** 接口鉴权 API Key */
    private final String apiKey;

    /**
     * 枚举构造方法
     *
     * @param type    厂商标识
     * @param baseUrl 接口基础地址
     * @param apiKey  鉴权 API Key
     */
    ModelProviderEnum(String type, String baseUrl, String apiKey) {
        this.type = type;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    /**
     * 根据厂商标识查找对应枚举值
     *
     * @param type 厂商标识，不区分大小写
     * @return 对应的枚举值
     * @throws BizException 当厂商标识不存在时抛出业务异常
     */
    public static ModelProviderEnum of(String type) {
        for (ModelProviderEnum provider : values()) {
            if (provider.type.equalsIgnoreCase(type)) {
                return provider;
            }
        }
        throw new BizException(400, "不支持的模型厂商: " + type);
    }

    /**
     * 获取厂商标识
     *
     * @return 厂商标识字符串
     */
    public String getType() {
        return type;
    }

    /**
     * 获取模型接口基础地址
     *
     * @return baseUrl 字符串
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 获取接口鉴权 API Key
     *
     * @return apiKey 字符串
     */
    public String getApiKey() {
        return apiKey;
    }
}
