package com.cl.agent.enums;

/**
 * 知识库文档解析状态枚举
 * <p>状态流转：uploading -> parsing -> indexed / failed。
 * {@code code} 即 {@code t_knowledge_document.status} 列与接口响应中的取值，前端依赖该字符串，不可随意修改。</p>
 */
public enum DocumentStatusEnum {

    /** 已上传落盘，等待异步向量化 */
    UPLOADING("uploading", "上传中"),

    /** 正在解析文件、切片并写入向量库 */
    PARSING("parsing", "解析中"),

    /** 切片与向量均已入库，可被检索（终态） */
    INDEXED("indexed", "已入库"),

    /** 解析或向量化失败，原因见 errorMessage（终态） */
    FAILED("failed", "失败");

    /** 状态码，与数据库及接口取值一致 */
    private final String code;

    /** 状态描述 */
    private final String desc;

    DocumentStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 根据状态码查找对应枚举值
     *
     * @param code 状态码，不区分大小写
     * @return 对应的枚举值；未匹配时返回 {@code null}
     */
    public static DocumentStatusEnum of(String code) {
        if (code == null) {
            return null;
        }
        for (DocumentStatusEnum status : values()) {
            if (status.code.equalsIgnoreCase(code.trim())) {
                return status;
            }
        }
        return null;
    }

    /**
     * 是否为终态（indexed / failed），终态文档不会再被异步流水线修改。
     */
    public boolean isFinal() {
        return this == INDEXED || this == FAILED;
    }
}
