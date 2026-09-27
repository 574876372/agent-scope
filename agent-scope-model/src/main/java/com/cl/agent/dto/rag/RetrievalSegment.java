package com.cl.agent.dto.rag;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 上下文扩展后交给模型的一段连续原文，即一条「引用来源」。
 * <p>由同一文档中相连的若干切片合并而成；{@code startIndex ~ endIndex} 为包含的切片序号区间，
 * {@code hitIndexes} 为其中被检索直接命中的切片。</p>
 */
@Data
public class RetrievalSegment implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 引用编号（1 起），与注入上下文中的 [n] 对应 */
    private Integer citation;

    /** 所属知识库 ID */
    private String kbId;

    /** 所属知识库名称 */
    private String kbName;

    /** 所属文档 ID */
    private String docId;

    /** 所属文档文件名 */
    private String docName;

    /** 章节路径，取段内首个带章节路径的切片；存量切片为空 */
    private String sectionPath;

    /** 起始切片序号（含） */
    private Integer startIndex;

    /** 结束切片序号（含） */
    private Integer endIndex;

    /** 段内被检索直接命中的切片序号 */
    private List<Integer> hitIndexes = new ArrayList<>();

    /** 扩展方式：none=不扩展 / window=相邻切片 / section=整章 / trimmed=超长被截断 */
    private String expandMode;

    /** 段内命中切片的最佳融合排名（1 起），决定超长时的舍弃顺序 */
    private Integer bestRank;

    /** 段内命中切片的最高得分（重排得分优先，否则为融合得分） */
    private Double score;

    /** 合并后的连续原文（相邻切片间的重叠部分已去重） */
    private String content;

    /** 原文字符数 */
    private Integer charCount;

    /**
     * 生成来源标注，如「接口文档.docx › 三、独立代发 › 请求参数 › 切片 #3-#6」。
     *
     * @return 来源标注文本
     */
    public String sourceLabel() {
        StringBuilder sb = new StringBuilder(docName == null ? "未知文档" : docName);
        if (sectionPath != null && !sectionPath.isBlank()) {
            sb.append(" › ").append(sectionPath);
        }
        sb.append(" › 切片 #").append(startIndex);
        if (endIndex != null && !endIndex.equals(startIndex)) {
            sb.append("-#").append(endIndex);
        }
        return sb.toString();
    }
}
