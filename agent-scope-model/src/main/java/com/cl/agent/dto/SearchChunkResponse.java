package com.cl.agent.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 语义检索召回切片的数据响应 DTO。
 * <p>主要由检索演练场 (Playground) 或引用来源面板展示，携带得分、文件名和原文。</p>
 */
@Data
public class SearchChunkResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 切片 ID */
    private String chunkId;

    /** 关联的文档唯一 ID */
    private String docId;

    /** 关联的文档文件名 */
    private String docName;

    /** 切片在文档中的物理顺序序号 (0-indexed) */
    private Integer chunkIndex;

    /** 该段落的纯文本内容 */
    private String content;

    /** 召回匹配相似度分数 (0.0~1.0) */
    private Double score;
}
