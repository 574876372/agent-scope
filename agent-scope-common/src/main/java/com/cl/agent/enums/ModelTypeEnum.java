package com.cl.agent.enums;

import com.cl.agent.exception.BizException;

/**
 * 模型用途类型枚举
 * <p>对应 {@code t_model.model_type}，区分对话模型与向量化模型。</p>
 */
public enum ModelTypeEnum {

    /** 对话模型，供智能体推理使用 */
    CHAT("对话模型"),

    /** 向量化模型，供知识库入库与检索使用 */
    EMBEDDING("向量模型");

    /** 类型描述 */
    private final String desc;

    ModelTypeEnum(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 根据类型标识查找对应枚举值
     *
     * @param code 类型标识，不区分大小写
     * @return 对应的枚举值
     * @throws BizException 类型不存在时抛出
     */
    public static ModelTypeEnum of(String code) {
        if (code != null) {
            for (ModelTypeEnum type : values()) {
                if (type.name().equalsIgnoreCase(code.trim())) {
                    return type;
                }
            }
        }
        throw new BizException(400, "不支持的模型类型: " + code);
    }
}
