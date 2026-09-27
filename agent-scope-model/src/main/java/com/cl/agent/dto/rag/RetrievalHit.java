package com.cl.agent.dto.rag;

import lombok.Data;

import java.io.Serializable;

/**
 * 检索流水线中的单个切片候选。
 * <p>向量召回、关键词召回、RRF 融合、重排序各阶段都以本类表示命中的切片，各阶段只填写自己的得分与排名；
 * 演练场据此分步展示中间结果。以 {@code docId + chunkIndex} 唯一标识一个切片。</p>
 */
@Data
public class RetrievalHit implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 切片 ID（MySQL 切片表主键）；向量库有而切片表缺失的旧数据为 null */
    private String chunkId;

    /** 所属知识库 ID */
    private String kbId;

    /** 所属知识库名称 */
    private String kbName;

    /** 所属文档 ID */
    private String docId;

    /** 所属文档文件名 */
    private String docName;

    /** 切片在文档内的序号（0 起） */
    private Integer chunkIndex;

    /** 章节路径；存量切片为空 */
    private String sectionPath;

    /** 切片类型：text / table / qa */
    private String chunkType;

    /** 切片原文 */
    private String content;

    /** 向量相似度（0 ~ 1）；未被向量召回时为 null */
    private Double vectorScore;

    /** 在向量召回结果中的排名（1 起）；未被向量召回时为 null */
    private Integer vectorRank;

    /** 全文索引相关度；未被关键词召回时为 null */
    private Double keywordScore;

    /** 在关键词召回结果中的排名（1 起）；未被关键词召回时为 null */
    private Integer keywordRank;

    /** RRF 融合得分 = Σ 1 / (k + 各路排名) */
    private Double fusedScore;

    /** 重排模型给出的相关度（0 ~ 1）；未启用重排时为 null */
    private Double rerankScore;

    /**
     * 切片的唯一键：文档 ID + 序号。
     *
     * @return 形如 {@code docId#3} 的键
     */
    public String key() {
        return docId + "#" + chunkIndex;
    }
}
