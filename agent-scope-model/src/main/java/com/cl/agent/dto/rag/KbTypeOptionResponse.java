package com.cl.agent.dto.rag;

import lombok.Data;

import java.io.Serializable;

/**
 * 知识库类型预设选项，供前端创建 / 编辑知识库时选择类型并展示默认参数。
 * <p>数值为已按「类型预设 → 全局默认」解析后的生效值。</p>
 */
@Data
public class KbTypeOptionResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 类型编码：GENERAL / TECH_DOC / FAQ / TABLE */
    private String code;

    /** 类型中文名 */
    private String label;

    /** 默认切片策略编码 */
    private String chunkStrategy;

    /** 默认切片策略中文名 */
    private String chunkStrategyLabel;

    /** 默认切片大小（字符） */
    private Integer chunkSize;

    /** 默认重叠字符数 */
    private Integer chunkOverlap;

    /** 默认上下文扩展窗口（前后各 N 片） */
    private Integer contextWindow;

    /** 章节不超上限时是否整章带入 */
    private Boolean wholeSection;

    /** 切片与扩展规则的简要说明 */
    private String description;
}
