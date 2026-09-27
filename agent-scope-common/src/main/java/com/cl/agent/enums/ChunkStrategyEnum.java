package com.cl.agent.enums;

/**
 * 文档切片策略枚举。
 * <p>对应 {@code t_knowledge_base.chunk_strategy}，由知识库类型预设（{@link KbTypePreset}）带出，也可在知识库级单独指定。
 * 所有策略都不会从表格行中间切断；表格超长时按行拆分并在每片重复表头。</p>
 */
public enum ChunkStrategyEnum {

    /** 按标题章节切分，章节内按段落累积至切片上限；通用文档与技术文档使用 */
    SECTION("按章节"),

    /** 一问一答为一片；FAQ 使用 */
    QA("一问一答"),

    /** 表格按行分组，每组重复表头；表格数据使用 */
    TABLE_ROW("按行分组");

    /** 策略中文描述，用于前端展示 */
    private final String label;

    ChunkStrategyEnum(String label) {
        this.label = label;
    }

    /**
     * 获取策略中文描述。
     *
     * @return 策略描述，如「按章节」
     */
    public String getLabel() {
        return label;
    }

    /**
     * 按名称解析策略，不区分大小写。
     *
     * @param code 策略名称，可为空
     * @return 对应枚举；为空或无法识别时返回 null，由调用方回落到类型预设
     */
    public static ChunkStrategyEnum ofNullable(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (ChunkStrategyEnum s : values()) {
            if (s.name().equalsIgnoreCase(code.trim())) {
                return s;
            }
        }
        return null;
    }
}
