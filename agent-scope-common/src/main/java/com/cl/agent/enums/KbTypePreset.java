package com.cl.agent.enums;

/**
 * 知识库类型预设枚举。
 * <p>对应 {@code t_knowledge_base.kb_type}。创建知识库时选择类型，自动带出切片与上下文扩展参数；
 * 知识库级参数为空时使用此处预设，预设中为 null 的项再回落到 yml 全局默认（{@code agent.rag.*}）。</p>
 * <p>扩展规则通用约束：所有扩展结果受「上下文总长度上限」约束，超出时按融合排名从低到高舍弃。</p>
 */
public enum KbTypePreset {

    /** 通用文档：按标题章节切分，章节内按段落累积；命中后补充前后各 1 片 */
    GENERAL("通用文档", ChunkStrategyEnum.SECTION, null, 1, false, false),

    /** 技术 / 接口文档：按章节切分，表格整体保留（超长按行拆分并重复表头）；所在章节不超上限时整章带入，否则前后各 2 片 */
    TECH_DOC("技术 / 接口文档", ChunkStrategyEnum.SECTION, 1000, 2, true, true),

    /** 问答 FAQ：一问一答为一片，不做上下文扩展 */
    FAQ("问答 FAQ", ChunkStrategyEnum.QA, null, 0, false, false),

    /** 表格数据：按行分组，每组重复表头；命中后补充前后各 1 片 */
    TABLE("表格数据", ChunkStrategyEnum.TABLE_ROW, 800, 1, false, false);

    /** 类型中文名，用于前端展示 */
    private final String label;

    /** 默认切片策略 */
    private final ChunkStrategyEnum chunkStrategy;

    /** 默认切片大小（字符）；null 表示使用 agent.rag.chunk-size */
    private final Integer chunkSize;

    /** 命中后向前、向后各补充的相邻切片数；0 表示不扩展 */
    private final int contextWindow;

    /** 命中切片所在章节总长不超过上下文上限时是否整章带入 */
    private final boolean wholeSection;

    /** 表格是否尽量整体保留为一片（仍受切片上限的 2 倍约束，超出按行拆分） */
    private final boolean keepTableWhole;

    KbTypePreset(String label, ChunkStrategyEnum chunkStrategy, Integer chunkSize, int contextWindow,
                 boolean wholeSection, boolean keepTableWhole) {
        this.label = label;
        this.chunkStrategy = chunkStrategy;
        this.chunkSize = chunkSize;
        this.contextWindow = contextWindow;
        this.wholeSection = wholeSection;
        this.keepTableWhole = keepTableWhole;
    }

    /**
     * 获取类型中文名。
     *
     * @return 类型中文名，如「通用文档」
     */
    public String getLabel() {
        return label;
    }

    /**
     * 获取该类型的默认切片策略。
     *
     * @return 切片策略，非空
     */
    public ChunkStrategyEnum getChunkStrategy() {
        return chunkStrategy;
    }

    /**
     * 获取该类型的默认切片大小。
     *
     * @return 切片大小（字符）；null 表示使用全局默认
     */
    public Integer getChunkSize() {
        return chunkSize;
    }

    /**
     * 获取命中后向前、向后各补充的相邻切片数。
     *
     * @return 相邻切片数，0 表示不扩展
     */
    public int getContextWindow() {
        return contextWindow;
    }

    /**
     * 是否在章节总长不超过上限时整章带入。
     *
     * @return true 表示优先整章带入
     */
    public boolean isWholeSection() {
        return wholeSection;
    }

    /**
     * 是否尽量把表格整体保留为一片。
     *
     * @return true 表示表格整体保留
     */
    public boolean isKeepTableWhole() {
        return keepTableWhole;
    }

    /**
     * 按名称解析类型，不区分大小写。
     *
     * @param code 类型名称，可为空
     * @return 对应枚举；为空或无法识别时返回 {@link #GENERAL}，保证存量知识库按通用文档处理
     */
    public static KbTypePreset of(String code) {
        if (code != null && !code.isBlank()) {
            for (KbTypePreset t : values()) {
                if (t.name().equalsIgnoreCase(code.trim())) {
                    return t;
                }
            }
        }
        return GENERAL;
    }
}
