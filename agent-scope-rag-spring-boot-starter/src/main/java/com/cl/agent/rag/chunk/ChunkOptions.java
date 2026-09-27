package com.cl.agent.rag.chunk;

import com.cl.agent.enums.ChunkStrategyEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单次切片的参数。
 * <p>由宿主按「知识库级配置 → 类型预设 → yml 全局默认」的优先级解析后传入 {@link StructuredChunker}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkOptions {

    /** 切片策略，非空 */
    private ChunkStrategyEnum strategy;

    /** 单个切片的最大字符数，须为正数；表格单行超过该值时整行保留，不会被切断 */
    private int chunkSize;

    /** 超长段落按句拆分时相邻切片的重叠字符数；0 表示不重叠 */
    private int chunkOverlap;

    /** 是否尽量把表格整体保留为一片（上限为切片大小的 2 倍，超出后按行拆分并重复表头） */
    private boolean keepTableWhole;
}
