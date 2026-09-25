package com.cl.agent.enums;

import com.cl.agent.exception.BizException;

/**
 * 模型厂商接口协议枚举
 * <p>对应 {@code t_model_provider.protocol}，决定构建哪种模型客户端。
 * 通义、DeepSeek、智谱、Ollama 等均提供 OpenAI 兼容接口，当前统一走 {@link #OPENAI}；
 * 后续如需接入厂商原生协议（如 DashScope、Ollama 原生接口），在此追加枚举值并补充对应的客户端构建逻辑。</p>
 */
public enum ModelProtocolEnum {

    /** OpenAI 兼容协议（{@code /chat/completions}、{@code /embeddings}） */
    OPENAI("OpenAI 兼容协议");

    /** 协议描述 */
    private final String desc;

    ModelProtocolEnum(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 根据协议标识查找对应枚举值
     *
     * @param code 协议标识，不区分大小写
     * @return 对应的枚举值
     * @throws BizException 协议不存在时抛出
     */
    public static ModelProtocolEnum of(String code) {
        if (code != null) {
            for (ModelProtocolEnum protocol : values()) {
                if (protocol.name().equalsIgnoreCase(code.trim())) {
                    return protocol;
                }
            }
        }
        throw new BizException(400, "不支持的接口协议: " + code);
    }
}
