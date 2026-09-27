package com.cl.agent.rag.chunk;

/**
 * 文档结构块类型。
 * <p>{@link DocumentStructureReader} 把各种格式的文件统一还原为「标题 / 段落 / 表格」三类结构块，
 * 再由 {@link StructuredChunker} 按知识库类型切片。</p>
 */
public enum DocBlockType {

    /** 标题，携带层级（1 为最高级），用于维护章节路径 */
    HEADING,

    /** 普通段落或代码块，切片时可与相邻段落合并 */
    PARAGRAPH,

    /** 表格，保留表头与行，切片时不会从行中间切断 */
    TABLE
}
