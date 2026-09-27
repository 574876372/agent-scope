package com.cl.agent.rag.chunk;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 结构化切片结果。
 * <p>由 {@link StructuredChunker} 产出；宿主据此写入向量库（向量化时在正文前拼接章节路径）与 MySQL 切片表。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkPiece {

    /** 切片类型：正文 */
    public static final String TYPE_TEXT = "text";

    /** 切片类型：表格（含重复的表头） */
    public static final String TYPE_TABLE = "table";

    /** 切片类型：一问一答 */
    public static final String TYPE_QA = "qa";

    /** 切片原文，展示与注入模型时使用 */
    private String content;

    /** 章节路径，如「三、独立代发 › 请求参数」；文档无标题结构时为空串 */
    private String sectionPath;

    /** 切片类型：{@link #TYPE_TEXT} / {@link #TYPE_TABLE} / {@link #TYPE_QA} */
    private String chunkType;
}
